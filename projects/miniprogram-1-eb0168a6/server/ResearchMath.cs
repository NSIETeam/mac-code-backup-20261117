using System.Xml.Linq;

namespace Siyue;

public static class ResearchMath
{
    static readonly XNamespace M = "http://schemas.openxmlformats.org/officeDocument/2006/math";
    public static XElement ToOfficeMath(string xml)
    {
        // XDocument does not resolve external entities with its default reader settings.
        var root = XElement.Parse(xml); return new XElement(M + "oMath", Convert(root));
    }
    static IEnumerable<XElement> Convert(XElement node)
    {
        var kids = node.Elements().ToArray();
        XElement Part(string name, int index) => new(M + name, index < kids.Length ? Convert(kids[index]) : []);
        switch (node.Name.LocalName) {
            case "math": case "mrow": case "mstyle": case "mpadded": case "menclose": return kids.SelectMany(Convert);
            case "semantics": return kids.Length > 0 ? Convert(kids[0]) : [];
            case "annotation": case "annotation-xml": case "mphantom": return [];
            case "mi": case "mn": case "mo": case "mtext": case "ms": return [new XElement(M + "r", new XElement(M + "t", node.Value))];
            case "mspace": return [new XElement(M + "r", new XElement(M + "t", " "))];
            case "mfrac": return [new XElement(M + "f", Part("num", 0), Part("den", 1))];
            case "msup": return [new XElement(M + "sSup", Part("e",0), Part("sup",1))];
            case "msub": return [new XElement(M + "sSub", Part("e",0), Part("sub",1))];
            case "msubsup": return [new XElement(M + "sSubSup", Part("e",0), Part("sub",1), Part("sup",2))];
            case "msqrt": return [new XElement(M + "rad", new XElement(M+"radPr",new XElement(M+"degHide",new XAttribute(M+"val","1"))),new XElement(M+"deg"),new XElement(M+"e",kids.SelectMany(Convert)))];
            case "mroot": return [new XElement(M + "rad", Part("deg",1),Part("e",0))];
            case "munder": return [new XElement(M+"limLow",Part("e",0),Part("lim",1))];
            case "mover": return [new XElement(M+"limUpp",Part("e",0),Part("lim",1))];
            case "munderover": return [new XElement(M+"limUpp",new XElement(M+"e",new XElement(M+"limLow",Part("e",0),Part("lim",1))),Part("lim",2))];
            case "mfenced": return [new XElement(M+"d",new XElement(M+"dPr",new XElement(M+"begChr",new XAttribute(M+"val",(string?)node.Attribute("open")??"(")),new XElement(M+"endChr",new XAttribute(M+"val",(string?)node.Attribute("close")??")"))),kids.Select(k=>new XElement(M+"e",Convert(k))))];
            case "mtable": return [new XElement(M+"m",kids.Select(k=>new XElement(M+"mr",k.Elements().Select(cell=>new XElement(M+"e",Convert(cell))))))];
            case "mtd": case "mtr": return kids.SelectMany(Convert);
            default: throw new ApiException(422,"公式包含尚不支持的结构，请核对后改为原稿图片保留");
        }
    }
}
