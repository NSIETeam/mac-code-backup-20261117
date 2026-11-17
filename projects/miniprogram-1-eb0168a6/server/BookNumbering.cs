using System.Text.RegularExpressions;
using System.Xml.Linq;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
namespace Siyue;

public static class BookNumbering
{
    static readonly XNamespace W = WordDocuments.W;
    // Resolve numbering over the entire book before slicing, so later lectures do not restart continuous question numbers.
    public static void Normalize(string path)
    {
        WordDocuments.CheckPackage(path);
        using var doc=WordprocessingDocument.Open(path,true);var main=doc.MainDocumentPart;
        if(main?.Document==null || main.NumberingDefinitionsPart?.Numbering==null)return;
        var root=XElement.Parse(main.Document.OuterXml);var numbering=XElement.Parse(main.NumberingDefinitionsPart.Numbering.OuterXml);
        var styles=main.StyleDefinitionsPart?.Styles is { } stylePart ? XElement.Parse(stylePart.OuterXml) : new XElement(W+"styles");
        var counters=new Dictionary<(string,int),int>();var changed=false;
        foreach(var p in (root.Element(W+"body") ?? throw new ApiException(422,"Word 缺少正文")).Descendants(W+"p")){
            var props=p.Element(W+"pPr");var np=props?.Element(W+"numPr");var numId=(string?)np?.Element(W+"numId")?.Attribute(W+"val");var level=(int?)np?.Element(W+"ilvl")?.Attribute(W+"val");
            var style=(string?)props?.Element(W+"pStyle")?.Attribute(W+"val");var visited=new HashSet<string>();
            while(style!=null && visited.Add(style) && (numId==null || level==null)){
                var definition=styles.Elements(W+"style").FirstOrDefault(x=>(string?)x.Attribute(W+"styleId")==style);var inherited=definition?.Element(W+"pPr")?.Element(W+"numPr");
                numId??=(string?)inherited?.Element(W+"numId")?.Attribute(W+"val");level??=(int?)inherited?.Element(W+"ilvl")?.Attribute(W+"val");style=(string?)definition?.Element(W+"basedOn")?.Attribute(W+"val");
            }
            if(numId==null || numId=="0")continue;var depth=level??0;
            var num=numbering.Elements(W+"num").FirstOrDefault(x=>(string?)x.Attribute(W+"numId")==numId);var abstractId=(string?)num?.Element(W+"abstractNumId")?.Attribute(W+"val");
            var original=numbering.Elements(W+"abstractNum").FirstOrDefault(x=>(string?)x.Attribute(W+"abstractNumId")==abstractId)?.Elements(W+"lvl").FirstOrDefault(x=>(int?)x.Attribute(W+"ilvl")==depth);
            var over=num?.Elements(W+"lvlOverride").FirstOrDefault(x=>(int?)x.Attribute(W+"ilvl")==depth);var definitionOverride=over?.Element(W+"lvl");
            var format=(string?)(definitionOverride?.Element(W+"lvlText")??original?.Element(W+"lvlText"))?.Attribute(W+"val");
            var type=(string?)(definitionOverride?.Element(W+"numFmt")??original?.Element(W+"numFmt"))?.Attribute(W+"val");
            var start=(int?)over?.Element(W+"startOverride")?.Attribute(W+"val")??(int?)(definitionOverride?.Element(W+"start")??original?.Element(W+"start"))?.Attribute(W+"val")??1;
            var number=counters.GetValueOrDefault((numId,depth),start-1)+1;counters[(numId,depth)]=number;
            foreach(var key in counters.Keys.Where(k=>k.Item1==numId && k.Item2>depth).ToArray())counters.Remove(key);
            if(format==null || type is not ("decimal" or "decimalZero" or "chineseCounting" or "chineseCountingThousand"))continue;
            var rendered=Regex.Replace(format,@"%([1-9])",m=>{
                var n=counters.GetValueOrDefault((numId,int.Parse(m.Groups[1].Value)-1),1);
                return type is "chineseCounting" or "chineseCountingThousand" ? Chinese(n) : type=="decimalZero"?n.ToString("D2"):n.ToString();
            });
            if(depth==0 && !WordDocuments.IsQuestionStart(rendered) && !Regex.IsMatch(rendered,@"^\s*第\s*[0-9一二三四五六七八九十百千零〇两]+\s*[讲课节]"))continue;
            if(props==null){props=new XElement(W+"pPr");p.AddFirst(props);}props.Element(W+"numPr")?.Remove();props.Add(new XElement(W+"numPr",new XElement(W+"ilvl",new XAttribute(W+"val",depth)),new XElement(W+"numId",new XAttribute(W+"val",0))));
            props.AddAfterSelf(new XElement(W+"r",new XElement(W+"t",new XAttribute(XNamespace.Xml+"space","preserve"),rendered+" ")));changed=true;
        }
        if(changed){main.Document=new Document(root.ToString(SaveOptions.DisableFormatting));main.Document.Save();}
    }
    static string Chinese(int n)
    {
        const string digits="零一二三四五六七八九";if(n is <1 or >999)return n.ToString();if(n<10)return digits[n].ToString();
        if(n<100)return (n<20?"":digits[n/10].ToString())+"十"+(n%10==0?"":digits[n%10].ToString());
        var tail=n%100;return digits[n/100]+"百"+(tail==0?"":tail<10?"零"+digits[tail]:tail<20?"一"+Chinese(tail):Chinese(tail));
    }
}
