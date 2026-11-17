using System.IO.Compression;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Security.Cryptography;
namespace Siyue;

public sealed partial class LocalQuestionBank
{
    sealed class BoundedReadStream(Stream source,long maximum) : Stream
    {
        long count;
        public override int Read(byte[] buffer,int offset,int size){var n=source.Read(buffer,offset,size);count+=n;if(count>maximum)throw new InvalidDataException("Index decompression limit");return n;}
        public override int Read(Span<byte> buffer){var n=source.Read(buffer);count+=n;if(count>maximum)throw new InvalidDataException("Index decompression limit");return n;}
        public override bool CanRead=>true;public override bool CanSeek=>false;public override bool CanWrite=>false;
        public override long Length=>throw new NotSupportedException();public override long Position{get=>count;set=>throw new NotSupportedException();}
        public override void Flush(){}public override long Seek(long offset,SeekOrigin origin)=>throw new NotSupportedException();public override void SetLength(long value)=>throw new NotSupportedException();public override void Write(byte[] buffer,int offset,int size)=>throw new NotSupportedException();
    }
    static string KeyHash(string value)=>Convert.ToHexStringLower(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(value)));
    sealed record IndexedPart(string Subject, string Stage, Part Index);
    sealed record IndexedDocument(string Id, string Type, string TemplateId, string MaterialId, string SourceKind,
        string[] KnowledgeIds, string[] ExplicitTags, string[] Tags, string Key, string PlainKey, string File);
    sealed record SavedIndex(string Schema, string Subject, string Stage, string RulesSha256, Part[] SourceParts,
        IndexedDocument[] Documents, Dictionary<string,int[]> Words, Dictionary<string,int[]> Tags, Dictionary<string,int[]> Knowledge);
    sealed class SmallCache<TKey,TValue>(int capacity) where TKey : notnull
    {
        readonly object sync = new();
        readonly Dictionary<TKey,(Lazy<TValue> Value,LinkedListNode<TKey> Node)> values = [];
        readonly LinkedList<TKey> order = [];
        public TValue Get(TKey key, Func<TValue> create)
        {
            Lazy<TValue> value;
            lock(sync) {
                if(values.TryGetValue(key,out var existing)){value=existing.Value;order.Remove(existing.Node);order.AddLast(existing.Node);}
                else {value=new(create,LazyThreadSafetyMode.ExecutionAndPublication);var node=order.AddLast(key);values.Add(key,(value,node));while(values.Count>capacity){var old=order.First!;values.Remove(old.Value);order.RemoveFirst();}}
            }
            try{return value.Value;}catch{lock(sync){if(values.TryGetValue(key,out var current)&&ReferenceEquals(current.Value,value)){values.Remove(key);order.Remove(current.Node);}}throw;}
        }
    }
    readonly SmallCache<string,BankQuestion[]> questionChunks = new(16);
    readonly SmallCache<string,QuestionMatch[]> queryCache = new(192);
    string RulesHash => Convert.ToHexStringLower(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(JsonSerializer.Serialize(rules,Options))));
    Scope BuildRawScope(string subject,string stage)
    {
        BankQuestion[] questions;var locations=new Dictionary<string,Part>(StringComparer.Ordinal);
        if(catalog==null)questions=legacy.Where(q=>q.Subject==subject&&q.Stage==stage).ToArray();
        else {
            var source=catalog.Subjects.SingleOrDefault(s=>s.Subject==subject&&s.Stage==stage);if(source==null)return new Scope([]);
            questions=source.Parts.SelectMany(part=>{var rows=ReadChecked<BankQuestion[]>(part);ValidateRows(rows);
                if(rows.Length!=part.Count||rows.Any(q=>q.Subject!=subject||q.Stage!=stage))throw new InvalidDataException("Scope mismatch");
                foreach(var q in rows)locations.Add(q.Id,part);return rows;}).ToArray();
            questions=Clean(questions).ToArray();if(questions.Length!=source.Count)throw new InvalidDataException("Catalog count mismatch");
        }
        return new Scope(questions.Select(q=>new Entry(q,KeyHash(Fingerprint(q.Subject,q.Stem,q.MaterialId)),Tokens(q.Stem+" "+string.Join(" ",q.Tags)),Tags(q.Subject,q.Stem,q.Tags),KeyHash(Fingerprint(q.Subject,q.Stem)))).ToArray(),locations);
    }
    Scope BuildScope(string subject,string stage)
    {
        var part=prebuiltIndexes?catalog?.Indexes?.SingleOrDefault(i=>i.Subject==subject&&i.Stage==stage):null;if(part==null)return BuildRawScope(subject,stage);
        var source=catalog!.Subjects.Single(s=>s.Subject==subject&&s.Stage==stage);
        var saved=ReadChecked<SavedIndex>(part.Index);
        if(saved.Schema!="siyue.bank-search/v2"||saved.Subject!=subject||saved.Stage!=stage||saved.RulesSha256!=RulesHash
            ||!saved.SourceParts.SequenceEqual(source.Parts))throw new InvalidDataException("Stale search index");
        var docs=saved.Documents;
        if(docs==null||docs.Length!=source.Count||docs.Select(d=>d.Id).Distinct().Count()!=docs.Length||docs.Any(d=>d==null||d.Id is not{Length:>0 and <=200}||!Types.Contains(d.Type)||d.KnowledgeIds==null||d.ExplicitTags==null||d.Tags==null||d.Key==null||d.PlainKey==null||!source.Parts.Any(p=>p.File==d.File)))throw new InvalidDataException("Invalid search index documents");
        foreach(var index in new[]{saved.Words,saved.Tags,saved.Knowledge}) {
            if(index==null||index.Count>500000)throw new InvalidDataException("Invalid postings");
            foreach(var (key,values)in index){if(key.Length is <1 or >1000||values==null||values.Length>docs.Length)throw new InvalidDataException("Invalid posting term");for(var i=0;i<values.Length;i++)if(values[i]<0||values[i]>=docs.Length||i>0&&values[i]<=values[i-1])throw new InvalidDataException("Invalid posting bounds");}
        }
        var empty=new HashSet<string>(StringComparer.Ordinal);
        var entries=docs.Select(d=>new Entry(new BankQuestion{Id=d.Id,Subject=subject,Stage=stage,Type=d.Type,TemplateId=d.TemplateId,MaterialId=d.MaterialId,SourceKind=d.SourceKind,KnowledgeIds=d.KnowledgeIds,Tags=d.ExplicitTags},d.Key,empty,d.Tags.ToHashSet(StringComparer.Ordinal),d.PlainKey)).ToArray();
        var files=source.Parts.ToDictionary(p=>p.File,StringComparer.Ordinal);
        return new Scope(entries,docs.ToDictionary(d=>d.Id,d=>files[d.File],StringComparer.Ordinal),saved.Words,saved.Tags,saved.Knowledge);
    }
    public void BuildSearchIndexes()
    {
        if(catalog==null)throw new InvalidOperationException("Expanded catalog required");
        var result=new List<IndexedPart>();
        foreach(var source in catalog.Subjects){
            var scope=BuildRawScope(source.Subject,source.Stage);
            var docs=scope.Entries.Select(e=>new IndexedDocument(e.Question.Id,e.Question.Type,e.Question.TemplateId,e.Question.MaterialId,e.Question.SourceKind,e.Question.KnowledgeIds,e.Question.Tags,e.Tags.Order(StringComparer.Ordinal).ToArray(),e.Key,e.PlainKey,scope.Details[e.Question.Id].File)).ToArray();
            var index=new SavedIndex("siyue.bank-search/v2",source.Subject,source.Stage,RulesHash,source.Parts,docs,scope.Words.OrderBy(p=>p.Key,StringComparer.Ordinal).ToDictionary(p=>p.Key,p=>p.Value),scope.Tags,scope.Knowledge);
            var file="indexes/"+Path.GetFileNameWithoutExtension(source.Parts[0].File).Split('-')[0]+".json.br";var target=Path.Combine(directory,file);Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            using(var output=File.Create(target+".pending"))using(var compressed=new BrotliStream(output,CompressionLevel.Optimal))JsonSerializer.Serialize(compressed,index,new JsonSerializerOptions(Options){Encoder=System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping});
            File.Move(target+".pending",target,true);var bytes=File.ReadAllBytes(target);result.Add(new(source.Subject,source.Stage,new(file,Convert.ToHexStringLower(SHA256.HashData(bytes)),source.Count)));
        }
        var manifest=JsonNode.Parse(File.ReadAllText(Path.Combine(directory,"manifest.json")))!;manifest["indexes"]=JsonSerializer.SerializeToNode(result,Options);var pending=Path.Combine(directory,"manifest.json.pending");File.WriteAllText(pending,manifest.ToJsonString());File.Move(pending,Path.Combine(directory,"manifest.json"),true);
    }
    static Dictionary<int,List<string>> TermHits(Scope scope,IEnumerable<string> tokens)
    {
        var hits=new Dictionary<int,List<string>>();foreach(var token in tokens)if(scope.Words.TryGetValue(token,out var positions))foreach(var i in positions){if(!hits.TryGetValue(i,out var words))hits.Add(i,words=[]);words.Add(token);}return hits;
    }
    BankQuestion Detail(BankQuestion header,Scope scope)
    {
        if(header.Stem.Length>0)return header;
        if(!scope.Details.TryGetValue(header.Id,out var file))throw new InvalidDataException("Question detail missing");
        var rows=questionChunks.Get(file.File,()=>{var data=ReadChecked<BankQuestion[]>(file);ValidateRows(data);if(data.Length!=file.Count)throw new InvalidDataException("Question detail count mismatch");return data;});
        var q=rows.SingleOrDefault(q=>q.Id==header.Id)??throw new InvalidDataException("Question detail ID mismatch");
        if(q.Subject!=header.Subject||q.Stage!=header.Stage||q.Type!=header.Type||q.TemplateId!=header.TemplateId||q.SourceKind!=header.SourceKind||q.MaterialId!=header.MaterialId||!q.KnowledgeIds.SequenceEqual(header.KnowledgeIds)||!q.Tags.SequenceEqual(header.Tags)||q.Issues.Length>0)throw new InvalidDataException("Search index/detail binding mismatch");
        return q;
    }
    public bool ContainsQuestion(BankQuestion question)
    {
        var scope=GetBuiltinScope(question.Subject,question.Stage);var key=KeyHash(Fingerprint(question.Subject,question.Stem));
        foreach(var entry in scope.Entries.Where(e=>e.PlainKey==key)){
            if(question.MaterialId.Length==0&&entry.Question.MaterialId.Length==0)return true;
            if(question.MaterialId.Length>0&&entry.Question.MaterialId.Length>0&&Hydrate(Detail(entry.Question,scope)).MaterialText==question.MaterialText)return true;
        }return false;
    }
}
