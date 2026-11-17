using System.Text.RegularExpressions;
using System.Xml.Linq;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.EntityFrameworkCore;
using Siyue;
using UglyToad.PdfPig;
using Xunit;

public class BookletLayoutTests
{
    static readonly string Qa = Path.GetFullPath(".local/acceptance/booklet-v14-20261104/generated");
    [Theory]
    [InlineData("analysis")] [InlineData("compilation")]
    public async Task HistoryAndClassroomUseTheSameHeadingAndMeasuredPagination(string layout)
    {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var (word, handout) = await Setup(f, scope.ServiceProvider);
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        var lecture = (await db.Lectures.FindAsync(handout.LectureId))!;
        var session = new ClassSession { ClassId = lecture.ClassId!.Value, CourseId = lecture.CourseId, HandoutId = handout.Id, LessonDate = "2026-10-29" };
        db.ClassSessions.Add(session); await db.SaveChangesAsync();
        var questions = new[] { Question(handout, 1, 2, true), Question(handout, 2, 2, false) };
        string[]? heading = null;
        foreach (var classroom in new[] { false, true }) {
            var export = Export(handout, questions); export.Layout = layout;
            export.CreatedAt = new DateTimeOffset(2026, 10, 29, 10, 0, 0, TimeSpan.FromHours(8));
            if (classroom) export.ClassSessionId = session.Id;
            var dir = Path.Combine(f.Root, "unified-" + classroom); Directory.CreateDirectory(dir);
            var (docx,pdf) = await word.BookletPdf(export, dir, default);
            Save(docx,pdf,$"unified-{layout}-{classroom}");
            using var rendered = PdfDocument.Open(pdf); Assert.Equal(1,rendered.NumberOfPages);
            var text = rendered.GetPage(1).Text;
            Assert.Contains("版式验收学生个性化错题",text); Assert.Contains("第1讲",text);
            Assert.Single(Regex.Matches(text,"日期")); Assert.Contains("2026.10.29",text);
            Assert.DoesNotContain("学生姓名：",text); Assert.Equal(2,Regex.Matches(text,"请列方程").Count);
            using var pack = WordprocessingDocument.Open(docx,false);
            var body = XElement.Parse(pack.MainDocumentPart!.Document!.OuterXml).Element(WordDocuments.W+"body")!;
            var current = body.Elements().Take(3).Select(x=>x.ToString()).ToArray();
            if (heading != null) Assert.Equal(heading,current); else heading=current;
            if(layout=="analysis") CheckBottom(rendered);
        }
    }
    [Theory]
    [InlineData(1, 2)] [InlineData(2, 2)] [InlineData(4, 1)]
    public async Task ShortQuestionsUseOneMetadataBlockAndFillPageBottom(int count, int paragraphs)
    {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var (word, handout) = await Setup(f, scope.ServiceProvider);
        var export = Export(handout, Enumerable.Range(1, count).Select(i => Question(handout, i, paragraphs, true)).ToArray());
        var (docx, pdf) = await word.BookletPdf(export, f.Root, CancellationToken.None);
        Save(docx, pdf, $"short-{count}"); Check(docx, pdf, count);
        using var result = PdfDocument.Open(pdf); Assert.Equal((count + 1) / 2, result.NumberOfPages);
        CheckBottom(result);
    }
    [Fact]
    public async Task AShortBlankOnlyTailIsRemovedBeforePageFill()
    {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var (word, handout) = await Setup(f, scope.ServiceProvider);
        var (docx, pdf) = await word.BookletPdf(Export(handout, [Question(handout, 1, 8, false)]), f.Root, default);
        Save(docx, pdf, "sparse-tail"); Check(docx, pdf, 1);
        using var result = PdfDocument.Open(pdf); Assert.Equal(1, result.NumberOfPages); CheckBottom(result);
        Assert.Contains("第07步", result.GetPage(1).Text);
    }
    [Fact]
    public async Task LongQuestionKeepsAllParagraphsAndOriginalSnapshot()
    {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var (word, handout) = await Setup(f, scope.ServiceProvider);
        var export = Export(handout, [Question(handout, 1, 40, false)]); var snapshot = export.SnapshotJson;
        var (docx, pdf) = await word.BookletPdf(export, f.Root, CancellationToken.None);
        Save(docx, pdf, "long-question"); Check(docx, pdf, 1); Assert.Equal(snapshot, export.SnapshotJson);
        using var result = PdfDocument.Open(pdf); Assert.True(result.NumberOfPages > 1);
        Assert.Contains("第39步", string.Concat(result.GetPages().Select(p => p.Text)));
    }
    [Theory]
    [InlineData("legacy")] [InlineData("analysis")] [InlineData("compilation")]
    public async Task RepeatedLabelsAndFormulaPictureContentSurviveTheNewTemplate(string layout)
    {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var (word, handout) = await Setup(f, scope.ServiceProvider);
        var sourcePath = Path.Combine(AppContext.BaseDirectory, "fixtures", "booklet-layout-source.docx");
        Assert.True(File.Exists(sourcePath));
        await using(var input=File.OpenRead(sourcePath)) await scope.ServiceProvider.GetRequiredService<IObjects>().Put(handout.NormalizedKey,input,WordDocuments.DocxType);
        using var source = WordprocessingDocument.Open(sourcePath, false); var w=WordDocuments.W;
        var root=XElement.Parse(source.MainDocumentPart!.Document!.OuterXml);
        var questions=root.Descendants(w+"tbl").First().Elements(w+"tr").Select((row,index)=> {
            var cell=row.Elements(w+"tc").Last();
            var blocks=cell.Elements(w+"p").TakeWhile(p=>!string.Concat(p.Descendants(w+"t").Select(t=>t.Value)).Contains("解题过程")).Select(WordDocuments.Block).ToArray();
            return new QuestionSnapshot(Guid.NewGuid(),handout.Id,index==1?"例1":"练1","第1讲 · 不应逐题显示",Json.Write(blocks),true);
        }).ToArray();
        var export=Export(handout,questions);
        if(layout!="legacy") {var db=scope.ServiceProvider.GetRequiredService<Data>();var lecture=await db.Lectures.FindAsync(handout.LectureId);var session=new ClassSession{ClassId=lecture!.ClassId!.Value,CourseId=lecture.CourseId,HandoutId=handout.Id,LessonDate="2026-10-27"};db.ClassSessions.Add(session);await db.SaveChangesAsync();export.ClassSessionId=session.Id;export.Layout=layout;}
        var (docx,pdf)=await word.BookletPdf(export,f.Root,default);
        Save(docx,pdf,"mixed-formula-image-"+layout);if(layout=="legacy")Check(docx,pdf,3);
        using var result=PdfDocument.Open(pdf);if(layout!="compilation")CheckBottom(result);
        Assert.Equal(1,result.GetPages().Sum(p=>p.NumberOfImages));
        using var target=WordprocessingDocument.Open(docx,false);
        Assert.Contains("oMath",target.MainDocumentPart!.Document!.OuterXml);
        var text=string.Concat(result.GetPages().Select(p=>p.Text));Assert.Equal(2,Regex.Matches(text,"练1").Count);Assert.Contains("例1",text);
    }
    [Theory]
    [InlineData(true)] [InlineData(false)]
    public async Task ClassroomLongCardsDoNotSpillOnlyAnswerSpaceOntoExtraPages(bool classroom)
    {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var (word, handout) = await Setup(f, scope.ServiceProvider);
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        var lecture = (await db.Lectures.FindAsync(handout.LectureId))!;
        var session = new ClassSession { ClassId = lecture.ClassId!.Value, CourseId = lecture.CourseId, HandoutId = handout.Id, LessonDate = "2026-10-27" };
        db.ClassSessions.Add(session); await db.SaveChangesAsync();
        var export = Export(handout, [Question(handout, 1, 18, false), Question(handout, 2, 20, false)]);
        if (classroom) export.ClassSessionId = session.Id; export.Layout = "analysis";
        var (docx, pdf) = await word.BookletPdf(export, f.Root, default);
        Save(docx, pdf, "classroom-long-cards");
        using var result = PdfDocument.Open(pdf);
        Assert.Equal(2, result.NumberOfPages);
        foreach (var page in result.GetPages()) Assert.Contains("请列方程", page.Text);
        Assert.Contains("第17步", result.GetPage(1).Text);
        Assert.Contains("第19步", result.GetPage(2).Text);
    }
    [Theory]
    [InlineData(1)] [InlineData(2)] [InlineData(5)]
    public async Task ClassroomUsesAtMostTwoEqualCardsAndFillsThePage(int count)
    {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var (word, handout) = await Setup(f, scope.ServiceProvider);
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        var lecture = (await db.Lectures.FindAsync(handout.LectureId))!;
        var session = new ClassSession { ClassId = lecture.ClassId!.Value, CourseId = lecture.CourseId, HandoutId = handout.Id, LessonDate = "2026-10-31" };
        db.ClassSessions.Add(session); await db.SaveChangesAsync();
        var export = Export(handout, Enumerable.Range(1, count).Select(i => Question(handout, i, 2, true)).ToArray());
        export.ClassSessionId = session.Id;
        var (docx, pdf) = await word.BookletPdf(export, f.Root, default);
        Save(docx, pdf, "balanced-" + count);
        using var result = PdfDocument.Open(pdf);
        Assert.Equal((count + 1) / 2, result.NumberOfPages); CheckBottom(result);
        var text = string.Concat(result.GetPages().Select(p => p.Text));
        Assert.Contains("版式验收学生个性化错题", text); Assert.Contains("第1讲", text);
        Assert.DoesNotContain("课程内容：", text); Assert.Contains("2026.10.31", text);
        Assert.Equal(result.NumberOfPages, Regex.Matches(text, "日期").Count);
        foreach (var page in result.GetPages()) Assert.InRange(Regex.Matches(page.Text, "请列方程").Count, 1, 2);
        using var pack = WordprocessingDocument.Open(docx, false); var w = WordDocuments.W;
        var body = XElement.Parse(pack.MainDocumentPart!.Document!.OuterXml).Element(w + "body")!;
        var frames = body.Elements(w + "tbl").Where(t => t.Element(w + "tblPr")!.Element(w + "tblBorders") != null);
        foreach (var frame in frames) {
            var rows = frame.Elements(w + "tr").ToArray();
            Assert.InRange(rows.Length, 1, 2);
            Assert.Single(rows.Select(r => (string?)r.Element(w + "trPr")!.Element(w + "trHeight")!.Attribute(w + "val")).Distinct());
            Assert.All(rows, row => {
                var left = string.Concat(row.Elements(w + "tc").First().Descendants(w + "t").Select(t => t.Value));
                var right = row.Elements(w + "tc").Last();
                Assert.Empty(right.Descendants(w + "pBdr"));
                Assert.Contains("卡点", left);
                Assert.Contains("套路总结：", left);
                Assert.DoesNotContain("粗心大意", left);
                Assert.Contains(right.Descendants(w + "t"), t => t.Value == "正解：");
                Assert.DoesNotContain(right.Descendants(w + "t"), t => t.Value.StartsWith("错因："));
            });
        }
    }
    [Theory]
    [InlineData(1, 5, 1)] [InlineData(5, 1, 1)]
    [InlineData(2, 2, 1)] [InlineData(1, 5, 2)]
    public async Task ClassroomPairsHalfPagePromptsRegardlessOfLegacyQuestionFlags(int firstParagraphs, int secondParagraphs, int pairs)
    {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var (word, handout) = await Setup(f, scope.ServiceProvider);
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        var lecture = (await db.Lectures.FindAsync(handout.LectureId))!;
        var session = new ClassSession { ClassId = lecture.ClassId!.Value, CourseId = lecture.CourseId, HandoutId = handout.Id, LessonDate = "2026-10-28" };
        db.ClassSessions.Add(session); await db.SaveChangesAsync();
        var questions = Enumerable.Range(0, pairs * 2).Select(i => {
            var paragraphs = i % 2 == 0 ? firstParagraphs : secondParagraphs;
            // Old answer-question flags must not force a full page.
            var question = Question(handout, i + 1, paragraphs, false);
            if (paragraphs == 1) {
                var w = WordDocuments.W;
                question = question with { BlocksJson = Json.Write(new[] { WordDocuments.Block(new XElement(w + "p", new XElement(w + "r",
                    new XElement(w + "t", $"{i + 1}. 下列计算正确的是（ ）。A. 2+3=5  B. 2+3=6")))) }) };
            }
            return question;
        }).ToArray();
        var export = Export(handout, questions); export.ClassSessionId = session.Id; export.Layout = "analysis";
        var snapshot = export.SnapshotJson;
        var (docx, pdf) = await word.BookletPdf(export, f.Root, default);
        Save(docx, pdf, $"paired-{firstParagraphs}-{secondParagraphs}-{pairs}");
        Assert.Equal(snapshot, export.SnapshotJson);
        using var result = PdfDocument.Open(pdf);
        Assert.Equal(pairs, result.NumberOfPages); CheckBottom(result);
        for (var i = 0; i < pairs; i++) {
            var page = result.GetPage(i + 1);
            Assert.Equal(2, Regex.Matches(page.Text, "正解：").Count);
            if (Math.Min(firstParagraphs, secondParagraphs) == 1) Assert.Contains("下列计算正确", page.Text);
            var first = page.Text.IndexOf($"{i * 2 + 1}.", StringComparison.Ordinal);
            var second = page.Text.IndexOf($"{i * 2 + 2}.", StringComparison.Ordinal);
            Assert.True(first >= 0 && second > first);
            if (Math.Max(firstParagraphs, secondParagraphs) == 5) Assert.Contains("第04步", page.Text);
        }
        using var pack = WordprocessingDocument.Open(docx, false); var w = WordDocuments.W;
        var frames = XElement.Parse(pack.MainDocumentPart!.Document!.OuterXml).Element(w + "body")!.Elements(w + "tbl")
            .Where(t => t.Element(w + "tblPr")!.Element(w + "tblBorders") != null);
        foreach (var frame in frames) {
            var rows = frame.Elements(w + "tr").ToArray(); Assert.Equal(2, rows.Length);
            var heights = rows.Select(r => int.Parse(r.Element(w + "trPr")!.Element(w + "trHeight")!.Attribute(w + "val")!.Value)).ToArray();
            Assert.Equal(heights[0], heights[1]);
        }
    }
    [Theory]
    [InlineData(1500)] [InlineData(400)]
    public async Task CompilationRestoresPdfWidthWithoutStretchingSmallDiagrams(int pixelWidth)
    {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var (word, handout) = await Setup(f, scope.ServiceProvider);
        var path = Path.Combine(f.Root, "raster-source.docx");
        var imagePath = Environment.GetEnvironmentVariable("SIYUE_ACCEPTANCE_PNG") ?? Path.Combine(AppContext.BaseDirectory,"fixtures",$"compilation-{pixelWidth}.png");
        var header=File.ReadAllBytes(imagePath);pixelWidth=(int)System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(16,4));
        var pixelHeight=(int)System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(20,4));
        QuestionSnapshot question;
        using (var source=WordprocessingDocument.Create(path,DocumentFormat.OpenXml.WordprocessingDocumentType.Document)) {
            var main=source.AddMainDocumentPart(); var image=main.AddImagePart(ImagePartType.Png);using(var stream=File.OpenRead(imagePath))image.FeedData(stream);
            var width=Math.Min(4_800_000L,pixelWidth*5080L);var height=width*pixelHeight/pixelWidth;
            var drawing=(Drawing)typeof(WordDocuments).GetMethod("PdfDrawing",System.Reflection.BindingFlags.NonPublic|System.Reflection.BindingFlags.Static)!.Invoke(null,[main.GetIdOfPart(image),(uint)1,width,height,true])!;
            var paragraph=new Paragraph(new Run(new RunProperties(new Vanish()),new Text("【1】 PDF 原题")),new Run(drawing));main.Document=new Document(new Body(paragraph));
            question=new(Guid.NewGuid(),handout.Id,"【1】","第1讲",Json.Write(new[]{WordDocuments.Block(XElement.Parse(paragraph.OuterXml))}),false);
        }
        await using(var stream=File.OpenRead(path))await scope.ServiceProvider.GetRequiredService<IObjects>().Put(handout.NormalizedKey,stream,WordDocuments.DocxType);
        var export=Export(handout,[question]);export.Layout="compilation";
        var (docx,pdf)=await word.BookletPdf(export,f.Root,default);Save(docx,pdf,"compilation-width-"+pixelWidth);
        using var result=PdfDocument.Open(pdf);var imageBounds=Assert.Single(result.GetPages().SelectMany(p=>p.GetImages())).BoundingBox;
        var expected=Math.Min(10318/20d,pixelWidth*72d/180);
        Assert.InRange(imageBounds.Width,expected-1,expected+1);Assert.InRange(imageBounds.Right,0,595.4-39);
        Assert.InRange(imageBounds.Height/imageBounds.Width,pixelHeight/(double)pixelWidth-.001,pixelHeight/(double)pixelWidth+.001);
    }
    [Theory][InlineData(false)][InlineData(true)]
    public async Task RealShortChoiceAndRadicalTableShareOnePage(bool reverse)
    {
        await using var f=new Factory();using var scope=f.Services.CreateScope();
        var (word,handout)=await Setup(f,scope.ServiceProvider);
        var sourcePath=Path.Combine(AppContext.BaseDirectory,"fixtures","short-and-table-source.docx");
        await using(var input=File.OpenRead(sourcePath))await scope.ServiceProvider.GetRequiredService<IObjects>().Put(handout.NormalizedKey,input,WordDocuments.DocxType);
        using var source=WordprocessingDocument.Open(sourcePath,false);
        var blocks=XElement.Parse(source.MainDocumentPart!.Document!.OuterXml).Element(WordDocuments.W+"body")!.Elements(WordDocuments.W+"p").Select(WordDocuments.Block).ToArray();
        var questions=blocks.Select((b,i)=>new QuestionSnapshot(Guid.NewGuid(),handout.Id,i==0?"【1】":"【练3-1】","第4讲",Json.Write(new[]{b}),false)).ToArray();
        if(reverse)Array.Reverse(questions);
        var export=Export(handout,questions);export.Layout="analysis";
        var (docx,pdf)=await word.BookletPdf(export,f.Root,default);Save(docx,pdf,"real-short-table-"+reverse);
        using var rendered=PdfDocument.Open(pdf);Assert.Equal(1,rendered.NumberOfPages);CheckBottom(rendered);
        Assert.Equal(2,rendered.GetPage(1).NumberOfImages);Assert.Equal(2,Regex.Matches(rendered.GetPage(1).Text,"正解：").Count);
        using var target=WordprocessingDocument.Open(docx,false);
        Assert.Equal(blocks.SelectMany(b=>WordDocuments.ImageReferences(b.Xml)).Distinct().Select(r=>{using var s=source.MainDocumentPart.GetPartById(r).GetStream();return Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(s));}).Order(),target.MainDocumentPart!.ImageParts.Select(p=>{using var s=p.GetStream();return Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(s));}).Order());
    }
    [Theory]
    [InlineData(5299, true)] [InlineData(5300, true)] [InlineData(5301, false)]
    public void HalfPageBoundaryIncludesEquality(int promptHeight, bool fits)
    {
        var method = typeof(WordDocuments).GetMethod("ClassroomFitsHalf", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static)!;
        Assert.Equal(fits, (bool)method.Invoke(null, [promptHeight, 12000])!);
    }

    [Fact]
    public async Task CurrentSessionHeadingUsesItsLectureAndLessonDateEvenForEarlierMistakes()
    {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var (word, handout) = await Setup(f, scope.ServiceProvider);
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        var original = (await db.Lectures.FindAsync(handout.LectureId))!;
        var current = new Lecture { ClassId = original.ClassId, CourseId = original.CourseId, Sequence = 3, Title = "本次讲次" };
        var currentHandout = new Handout { LectureId = current.Id, State = "published", NormalizedKey = handout.NormalizedKey, Version = 1 };
        var session = new ClassSession { ClassId = original.ClassId!.Value, CourseId = original.CourseId, HandoutId = currentHandout.Id, LessonDate = "2026-11-04" };
        db.AddRange(current, currentHandout, session); await db.SaveChangesAsync();
        var questions = new[] { Question(handout, 1, 2, true) };
        var export = Export(handout, questions); export.ClassSessionId = session.Id;
        var method = typeof(WordDocuments).GetMethod("BookletMetadata", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance)!;
        var metadata = await (Task<string[]>)method.Invoke(word, [export, questions, CancellationToken.None])!;
        Assert.Equal(new[] { "版式验收学生个性化错题", "第3讲", "日期：2026.11.04" }, metadata);
    }

    [Theory]
    [InlineData(8, 8, 1)] [InlineData(1, 20, 2)] [InlineData(20, 1, 2)]
    public async Task PromptHeightAloneDecidesPairsAndLongQuestionsNeverBorrowSpace(int first, int second, int pages)
    {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var (word, handout) = await Setup(f, scope.ServiceProvider);
        var (docx, pdf) = await word.BookletPdf(Export(handout, [Question(handout, 1, first, false), Question(handout, 2, second, false)]), f.Root, default);
        Save(docx, pdf, $"threshold-{first}-{second}");
        using var rendered = PdfDocument.Open(pdf);
        Assert.Equal(pages, rendered.NumberOfPages); CheckBottom(rendered);
        Assert.Equal(2, rendered.GetPages().Sum(p => Regex.Matches(p.Text, "正解：").Count));
        foreach (var page in rendered.GetPages()) {
            var words = page.GetWords().ToArray();
            var title = Assert.Single(words, w => w.Text.Contains("个性化错题"));
            Assert.Contains("第1讲", page.Text);
            var headerLetters = page.Letters.Where(l => Math.Abs(l.GlyphRectangle.Bottom-title.BoundingBox.Bottom)<10).ToArray();
            var lesson = Assert.Single(headerLetters, l => l.Value == "第");
            var date = Assert.Single(headerLetters, l => l.Value == "日");
            Assert.InRange(title.BoundingBox.Left, 38, 42);
            Assert.True(lesson.GlyphRectangle.Left > title.BoundingBox.Right);
            Assert.True(date.GlyphRectangle.Left > lesson.GlyphRectangle.Right);
            Assert.InRange(Math.Abs(lesson.GlyphRectangle.Bottom-date.GlyphRectangle.Bottom), 0, 2);
            Assert.Contains("一级错误（低级）", page.Text); Assert.Contains("二级错误（必须掌握）", page.Text); Assert.Contains("三级错误", page.Text);
            Assert.DoesNotContain("其他原因", page.Text);
        }
    }

    [Theory]
    [InlineData(true, false)] [InlineData(false, true)] [InlineData(true, true)]
    public async Task ShortPromptsIgnoreOuterBlankParagraphsAndInheritedPagination(bool blanks, bool inheritedStyle)
    {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var (word, handout) = await Setup(f, scope.ServiceProvider); var w = WordDocuments.W;
        var sourcePath = Path.Combine(f.Root, "flow-source.docx");
        using (var source = WordprocessingDocument.Create(sourcePath, DocumentFormat.OpenXml.WordprocessingDocumentType.Document)) {
            var main = source.AddMainDocumentPart(); main.Document = new Document(new Body(new Paragraph(new Run(new Text("原始讲义不改写")))));
            main.AddNewPart<StyleDefinitionsPart>().Styles = new Styles(new Style(new StyleParagraphProperties(new PageBreakBefore(), new KeepNext(), new KeepLines(), new SpacingBetweenLines { Before = "5000", BeforeAutoSpacing = true })) { Type = StyleValues.Paragraph, StyleId = "ForcePage" });
        }
        var originalBytes = File.ReadAllBytes(sourcePath);
        await using (var input = File.OpenRead(sourcePath)) await scope.ServiceProvider.GetRequiredService<IObjects>().Put(handout.NormalizedKey, input, WordDocuments.DocxType);
        var questions = Enumerable.Range(1, 2).Select(i => {
            var blocks = new List<ContentBlock>();
            if (blanks) for (var n = 0; n < 24; n++) blocks.Add(WordDocuments.Block(new XElement(w + "p", new XElement(w + "r", new XElement(w + "t", " ")))));
            var props = new XElement(w + "pPr"); if (inheritedStyle) props.Add(new XElement(w + "pStyle", new XAttribute(w + "val", "ForcePage")));
            blocks.Add(WordDocuments.Block(new XElement(w + "p", props, new XElement(w + "r", new XElement(w + "t", $"{i}. 短题条件完整保留：计算2+3。")))));
            // Internal separation stays intact; only outer blank paragraphs are removed.
            blocks.Add(WordDocuments.Block(new XElement(w + "p")));
            blocks.Add(WordDocuments.Block(new XElement(w + "p", new XElement(w + "r", new XElement(w + "t", "选项A为5，选项B为6。")))));
            if (blanks) for (var n = 0; n < 24; n++) blocks.Add(WordDocuments.Block(new XElement(w + "p")));
            return new QuestionSnapshot(Guid.NewGuid(), handout.Id, i + ".", "第1讲", Json.Write(blocks), false);
        }).ToArray();
        var export = Export(handout, questions); var snapshot = export.SnapshotJson;
        var (docx, pdf) = await word.BookletPdf(export, f.Root, default); Save(docx, pdf, $"flow-{blanks}-{inheritedStyle}");
        Assert.Equal(snapshot, export.SnapshotJson); Assert.Equal(originalBytes, File.ReadAllBytes(sourcePath));
        using var rendered = PdfDocument.Open(pdf); Assert.Equal(1, rendered.NumberOfPages); CheckBottom(rendered);
        Assert.Equal(2, Regex.Matches(rendered.GetPage(1).Text, "短题条件完整保留").Count);
        Assert.Equal(2, Regex.Matches(rendered.GetPage(1).Text, "选项A为5").Count);
        Assert.Equal(2, Regex.Matches(rendered.GetPage(1).Text, "正解：").Count);
        using var pack = WordprocessingDocument.Open(docx, false);
        var rows = XElement.Parse(pack.MainDocumentPart!.Document!.OuterXml).Element(w + "body")!.Elements(w + "tbl").SelectMany(t => t.Elements(w + "tr")).ToArray();
        Assert.Equal(2, rows.Length);
        foreach (var row in rows) {
            var paragraphs = row.Elements(w + "tc").Last().Elements(w + "p").ToArray();
            Assert.Equal(4, paragraphs.Length); // two source paragraphs, internal gap, 正解 label
            Assert.Equal("0", (string?)paragraphs[0].Element(w + "pPr")?.Element(w + "pageBreakBefore")?.Attribute(w + "val"));
        }
    }

    [Fact]
    public async Task HistoricBookletReflowIsIdempotentAndPreservesOldPdf()
    {
        await using var f = new Factory(); using var admin = await f.Login("layout-admin", true);
        using var scope = f.Services.CreateScope(); var (_, handout) = await Setup(f, scope.ServiceProvider);
        var db = scope.ServiceProvider.GetRequiredService<Data>(); var objects = scope.ServiceProvider.GetRequiredService<IObjects>();
        var old = Export(handout, [Question(handout, 1, 2, false), Question(handout, 2, 2, false)]);
        old.StaffId = (await db.Staff.SingleAsync(s => s.Role == "admin")).Id;
        old.State = "ready"; old.TemplateVersion = "v14-personalized-heading-analysis"; old.PdfKey = "old/immutable.pdf";
        var previous = System.Text.Encoding.UTF8.GetBytes("original downloaded PDF must remain immutable");
        await objects.Put(old.PdfKey, new MemoryStream(previous), "application/pdf");
        db.Documents.Add(old); await db.SaveChangesAsync();
        var first = await Factory.Post(admin, $"/api/exports/{old.Id}/reflow", new {});
        var nextId = first.GetProperty("id").GetGuid(); Assert.NotEqual(old.Id, nextId);
        Assert.Equal(nextId, (await Factory.Post(admin, $"/api/exports/{old.Id}/reflow", new {})).GetProperty("id").GetGuid());
        Assert.Equal(1, await db.Work.CountAsync(w => w.EntityId == nextId && w.Kind == "export"));
        await f.Run("export", nextId); db.ChangeTracker.Clear();
        var next = await db.Documents.SingleAsync(d => d.Id == nextId);
        Assert.Equal("ready", next.State); Assert.Equal(WordDocuments.BookletTemplateVersion, next.TemplateVersion);
        await using var currentStream = await objects.Get(next.PdfKey); using var currentBytes = new MemoryStream(); await currentStream.CopyToAsync(currentBytes);
        using var pdf = PdfDocument.Open(currentBytes.ToArray()); Assert.Equal(1, pdf.NumberOfPages);
        Assert.Equal(2, Regex.Matches(pdf.GetPage(1).Text, "正解：").Count);
        await using var oldStream = await objects.Get(old.PdfKey); using var oldBytes = new MemoryStream(); await oldStream.CopyToAsync(oldBytes);
        Assert.Equal(previous, oldBytes.ToArray());
        var savedOld = await db.Documents.SingleAsync(d => d.Id == old.Id); Assert.Equal(old.TemplateVersion, savedOld.TemplateVersion);
        Assert.False((await Factory.Post(admin, $"/api/exports/{nextId}/reflow", new {})).GetProperty("needsReflow").GetBoolean());
    }

    static void CheckBottom(PdfDocument pdf) {
        foreach(var page in pdf.GetPages()) {
            var borders=page.Paths.SelectMany(path=>path).SelectMany(path=>path.Commands).Select(c=>c.GetBoundingRectangle()).Where(b=>b.HasValue && b.Value.Width>500 && b.Value.Height<1 && b.Value.Bottom>20).Select(b=>b!.Value).ToArray();
            Assert.NotEmpty(borders); var bottom=borders.Min(b=>b.Bottom)*25.4/72;
            Assert.InRange(bottom,14.5,17.0);
        }
    }
    static void Check(string docx,string path,int questions) {
        using var pdf=PdfDocument.Open(path);var text=string.Concat(pdf.GetPages().Select(p=>p.Text));
        Assert.InRange(Regex.Matches(text,"日期").Count,1,pdf.NumberOfPages);Assert.Equal(Regex.Matches(text,"日期").Count,Regex.Matches(text,"个性化错题").Count);
        Assert.Contains("版式验收学生个性化错题",text);Assert.DoesNotContain("课程内容：",text);Assert.DoesNotContain("姓名：",text);
        Assert.DoesNotContain("SYLAYOUT",text);Assert.DoesNotContain("原题 ·",text);Assert.DoesNotContain("错题集",text);
        using var pack=WordprocessingDocument.Open(docx,false);var w=WordDocuments.W;var root=XElement.Parse(pack.MainDocumentPart!.Document!.OuterXml);
        Assert.Equal("850",(string?)root.Descendants(w+"pgMar").Single().Attribute(w+"bottom"));
        var rows=root.Element(w+"body")!.Elements(w+"tbl").Where(t=>t.Element(w+"tblPr")?.Element(w+"tblBorders")!=null).SelectMany(t => t.Elements(w+"tr")).ToArray();Assert.Equal(questions,rows.Length);
        foreach(var row in rows) {
            var right=row.Elements(w+"tc").Last();Assert.Empty(right.Descendants(w+"pBdr"));
            Assert.Contains(right.Descendants(w+"t"),t=>t.Value=="正解：");
            var left = row.Elements(w+"tc").First();
            var analysis = string.Concat(left.Descendants(w+"t").Select(t=>t.Value));
            foreach (var cause in new[] { "概念模糊", "思路错误", "计算错误", "审题错误", "时间不够", "太难了" }) Assert.Contains("□ " + cause, analysis);
            foreach (var label in new[] { "正确思路：", "卡点（哪一步不会）：", "套路总结：" }) Assert.Contains(label, analysis);
            Assert.DoesNotContain("没有思路", analysis);
            foreach (var pair in new[] { new[] { "计算错误", "审题错误" }, new[] { "思路错误", "概念模糊" } }) {
                var paired = Assert.Single(left.Elements(w+"p"), p => string.Concat(p.Descendants(w+"t").Select(t=>t.Value)).Contains(pair[0]));
                Assert.Contains(pair[1], string.Concat(paired.Descendants(w+"t").Select(t=>t.Value)));
                Assert.Single(paired.Elements(w+"r").Elements(w+"tab"));
            }
            Assert.DoesNotContain("粗心大意", analysis);
            Assert.NotEmpty(left.Descendants(w+"pBdr"));
            var paragraph=right.Elements(w+"p").First();
            Assert.Equal("360",(string?)paragraph.Element(w+"pPr")?.Element(w+"spacing")?.Attribute(w+"line"));
        }
    }
    static void Save(string docx, string pdf, string name) { Directory.CreateDirectory(Qa); File.Copy(docx, Path.Combine(Qa, name + ".docx"), true); File.Copy(pdf, Path.Combine(Qa, name + ".pdf"), true); }
    static QuestionSnapshot Question(Handout h, int index, int paragraphs, bool half)
    {
        var w = WordDocuments.W;
        var blocks = Enumerable.Range(0, paragraphs).Select(i => WordDocuments.Block(new XElement(w + "p", new XElement(w + "r", new XElement(w + "t", i == 0 ? $"{index}. 请列方程解答下列问题。" : $"第{i:D2}步：保留完整条件，写出计算过程。"))))).ToArray();
        return new(Guid.NewGuid(), h.Id, index + ".", "第1讲 方程与应用", Json.Write(blocks), half);
    }
    static ExportDocument Export(Handout h, QuestionSnapshot[] questions) => new() { StudentName = "版式验收学生", SnapshotJson = Json.Write(questions) };
    static async Task<(WordDocuments, Handout)> Setup(Factory f, IServiceProvider services)
    {
        var db = services.GetRequiredService<Data>();
        var campus = new Campus { Name = "版式校区" }; var course = new Course { Name = "数学" }; var schoolClass = new SchoolClass { Name = "版式班", CampusId = campus.Id };
        var lecture = new Lecture { ClassId = schoolClass.Id, CourseId = course.Id, Sequence = 1, Title = "方程" };
        var handout = new Handout { LectureId = lecture.Id, State = "published", NormalizedKey = "layout/source.docx", Version = 1 };
        db.AddRange(campus, course, schoolClass, lecture, handout); await db.SaveChangesAsync();
        var path = Path.Combine(f.Root, "source.docx");
        using (var pack = WordprocessingDocument.Create(path, DocumentFormat.OpenXml.WordprocessingDocumentType.Document)) { pack.AddMainDocumentPart().Document = new Document(new Body(new Paragraph(new Run(new Text("版式验收"))))); }
        await using var file = File.OpenRead(path); await services.GetRequiredService<IObjects>().Put(handout.NormalizedKey, file, WordDocuments.DocxType);
        return (services.GetRequiredService<WordDocuments>(), handout);
    }
}
