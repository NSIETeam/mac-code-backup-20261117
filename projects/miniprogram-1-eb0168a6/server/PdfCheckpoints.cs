using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using System.Xml.Linq;
namespace Siyue;
public partial class WordDocuments
{
    async Task<int?> RestorePdfPage(string key,string dir,MainDocumentPart main,Body body,CancellationToken ct) {
        var path=Path.Combine(dir,"cached-page.docx");
        try {await Materialize(key,dir,"cached-page.docx",ct);}
        catch(Exception e) when(e is FileNotFoundException or DirectoryNotFoundException || e is Amazon.S3.AmazonS3Exception s && s.StatusCode==System.Net.HttpStatusCode.NotFound){return null;}
        // Corrupt/incomplete checkpoints are recomputed; no partially restored page is kept.
        using var cached=WordprocessingDocument.Open(path,false);var source=cached.MainDocumentPart ?? throw new InvalidDataException("Missing page content");
        var nodes=(source.Document?.Body ?? throw new InvalidDataException("Missing page body")).Elements<Paragraph>().Select(p=>new XElement(XElement.Parse(p.OuterXml))).ToArray();
        foreach(var xml in nodes)foreach(var attr in xml.DescendantsAndSelf().Attributes().Where(x=>x.Name.Namespace==R).ToArray()) {
            var part=source.GetPartById(attr.Value);attr.Value=main.GetIdOfPart(main.AddPart(part));
        }
        foreach(var xml in nodes)body.Append(new Paragraph(xml.ToString(SaveOptions.DisableFormatting)));
        return nodes.Count(n=>n.Descendants(WP+"docPr").Any(p=>(string?)p.Attribute("descr")=="question"));
    }
    async Task CachePdfPage(string key,string dir,MainDocumentPart source,Body body,int start,CancellationToken ct) {
        var elements=body.ChildElements.Skip(start).OfType<Paragraph>().ToArray();
        var assistReport=Path.Combine(dir,"pdf-model-"+Path.GetFileNameWithoutExtension(key)+".json");
        if(File.Exists(assistReport)) {
            using var report=System.Text.Json.JsonDocument.Parse(await File.ReadAllTextAsync(assistReport,ct));
            var status=report.RootElement.GetProperty("status").GetString();
            if(status is not ("reviewed" or "supplemented"))return; // A failed optional service must be retryable on re-import.
        }
        if(elements.Any(p=>IsPdfReview(XElement.Parse(p.OuterXml))))return; // Retry unresolved pages next time.
        var path=Path.Combine(dir,"save-page.docx");
        using(var pack=WordprocessingDocument.Create(path,WordprocessingDocumentType.Document)) {
            var main=pack.AddMainDocumentPart();var target=new Body();main.Document=new Document(target);
            var relationships=new Dictionary<string,string>();
            foreach(var element in elements) {
                var xml=XElement.Parse(element.OuterXml);
                foreach(var attr in xml.DescendantsAndSelf().Attributes().Where(x=>x.Name.Namespace==R).ToArray()) {
                    if(!relationships.TryGetValue(attr.Value,out var rid)){rid=main.GetIdOfPart(main.AddPart(source.GetPartById(attr.Value)));relationships[attr.Value]=rid;}
                    attr.Value=rid;
                }
                target.Append(new Paragraph(xml.ToString(SaveOptions.DisableFormatting)));
            }
            main.Document.Save();
        }
        // Put a unique data object first, then its small pointer atomically. Local filesystem
        // writes use rename to prevent concurrent workers observing half a DOCX.
        var dataKey=key+"."+Guid.NewGuid().ToString("N");
        await using(var file=File.OpenRead(path))await objects.Put(dataKey,file,DocxType,ct);
        await using var pointer=new MemoryStream(System.Text.Encoding.UTF8.GetBytes(dataKey));await objects.Put(key+".pointer",pointer,"text/plain",ct);
    }
    async Task<int?> LoadPdfCheckpoint(string key,string dir,MainDocumentPart main,Body body,CancellationToken ct) {
        string actual;
        try {await using var stream=await objects.Get(key+".pointer",ct);using var reader=new StreamReader(stream);actual=await reader.ReadToEndAsync(ct);}
        catch(Exception e) when(e is FileNotFoundException or DirectoryNotFoundException || e is Amazon.S3.AmazonS3Exception s && s.StatusCode==System.Net.HttpStatusCode.NotFound){return null;}
        if(!actual.StartsWith(key+".",StringComparison.Ordinal))return null;
        var count=body.ChildElements.Count;var parts=main.Parts.Select(x=>x.RelationshipId).ToHashSet();
        try{return await RestorePdfPage(actual,dir,main,body,ct);}
        catch(Exception e) when(e is InvalidDataException or System.Xml.XmlException or OpenXmlPackageException or ArgumentException or System.IO.FileFormatException) {
            while(body.ChildElements.Count>count)body.LastChild!.Remove();
            foreach(var part in main.Parts.Where(x=>!parts.Contains(x.RelationshipId)).ToArray())main.DeletePart(part.RelationshipId);
            return null;
        }
    }
}
