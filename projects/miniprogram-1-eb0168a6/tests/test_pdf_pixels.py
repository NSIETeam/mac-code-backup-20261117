"""Pixel checks use the rendered source, including diagrams absent from text extraction."""
import importlib.util
import pathlib
import tempfile
import unittest
from PIL import Image, ImageDraw

spec = importlib.util.spec_from_file_location('pdf_pixels', pathlib.Path(__file__).parents[1] / 'server/pdf_pixels.py')
pixels = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pixels)

class PixelIntegrityTests(unittest.TestCase):
    def test_title_padding_does_not_merge_question_but_actual_ink_still_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            image=Image.new('RGB',(600,800),'white');draw=ImageDraw.Draw(image)
            draw.rectangle((40,80,300,110),fill='black');draw.rectangle((40,220,400,245),fill='black')
            anchors=[{'index':0,'left':40,'right':300,'top':80,'bottom':110,'context':True},
                     {'index':1,'left':40,'right':400,'top':220,'bottom':245}]
            path=pathlib.Path(tmp)/'title.png';image.save(path)
            plan={'anchors':anchors,'graphics':[[40,80,400,260]]}
            result=pixels.layout(path,plan)
            self.assertEqual([r['anchors'] for r in result['regions']],[[0],[1]])
            self.assertEqual(result['missingPixels'],0)
            draw.line((500,80,500,260),fill='black',width=2);image.save(path)
            result=pixels.layout(path,plan)
            self.assertEqual([r['anchors'] for r in result['regions']],[[0,1]])
            self.assertEqual(result['missingPixels'],0)

    def test_columns_read_down_left_then_down_right_without_cutting_figures(self):
        with tempfile.TemporaryDirectory() as tmp:
            image=Image.new('RGB',(600,800),'white');draw=ImageDraw.Draw(image)
            anchors=[]
            for index,(x,y) in enumerate([(40,100),(40,430),(340,130),(340,460)]):
                draw.rectangle((x,y,x+180,y+20),fill='black')
                anchors.append({'index':index,'left':x,'right':x+180,'top':y,'bottom':y+20})
            draw.rectangle((40,180,240,350),outline='black',width=2)
            path=pathlib.Path(tmp)/'columns.png';image.save(path)
            result=pixels.layout(path,{'anchors':anchors,'graphics':[[40,180,240,350]]})
            self.assertIsNone(result['error']);self.assertEqual(result['missingPixels'],0)
            self.assertEqual(result['columns'],2)
            self.assertEqual([r['anchors'] for r in result['regions']],[[0],[1],[2],[3]])
            self.assertGreaterEqual(result['regions'][0]['bounds'][3],351)

    def test_shared_diagram_groups_only_affected_questions(self):
        with tempfile.TemporaryDirectory() as tmp:
            image=Image.new('RGB',(600,800),'white');draw=ImageDraw.Draw(image)
            anchors=[]
            for index,y in enumerate([50,200,340,660]):
                draw.rectangle((40,y,280,y+20),fill='black')
                anchors.append({'index':index,'left':40,'right':280,'top':y,'bottom':y+20})
            draw.rectangle((300,185,540,530),outline='black',width=2)
            path=pathlib.Path(tmp)/'shared.png';image.save(path)
            result=pixels.layout(path,{'anchors':anchors,'graphics':[[300,185,540,530]]})
            self.assertEqual(result['missingPixels'],0)
            self.assertEqual([r['anchors'] for r in result['regions']],[[0],[1,2],[3]])
            self.assertIn('merged-region',result['regions'][1]['checks'])

    def test_diagram_crossing_columns_is_never_cut(self):
        with tempfile.TemporaryDirectory() as tmp:
            image=Image.new('RGB',(600,800),'white');draw=ImageDraw.Draw(image)
            anchors=[{'index':0,'left':40,'right':200,'top':100,'bottom':120},
                     {'index':1,'left':340,'right':520,'top':100,'bottom':120}]
            for a in anchors:draw.rectangle((a['left'],a['top'],a['right'],a['bottom']),fill='black')
            draw.rectangle((80,300,520,500),outline='black',width=2)
            path=pathlib.Path(tmp)/'span.png';image.save(path)
            result=pixels.layout(path,{'anchors':anchors,'graphics':[[80,300,520,500]]})
            self.assertIsNotNone(result['error']);self.assertEqual(result['regions'],[])

    def test_unassigned_diagram_is_reported_even_without_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            image = Image.new('RGB', (200, 300), 'white')
            ImageDraw.Draw(image).rectangle((100, 150, 150, 200), fill='black')
            path = pathlib.Path(tmp) / 'page.png'; image.save(path)
            result = pixels.audit(path, {'width':200,'height':300,'regions':[[0,0,200,100]],'cuts':[]})
            self.assertEqual(result['missingPixels'], 51*51)
            self.assertEqual(result['missingBounds'], (100,150,151,201))

    def test_diagram_crossing_crop_edge_blocks_but_unrelated_side_ink_does_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            image = Image.new('RGB', (200, 300), 'white')
            ImageDraw.Draw(image).line((120,50,120,250), fill='black', width=4)
            path = pathlib.Path(tmp) / 'page.png'; image.save(path)
            plan = {'width':200,'height':300,'regions':[[0,0,200,300]],'cuts':[[0,150,200]]}
            self.assertGreater(pixels.audit(path, plan)['cutInk'], 4)
            plan['cuts'] = [[0,150,90]]
            self.assertEqual(pixels.audit(path, plan)['cutInk'], 0)

    def test_blank_rows_are_detected_without_relying_on_ocr_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            image = Image.new('RGB', (200, 300), 'white')
            draw = ImageDraw.Draw(image); draw.rectangle((20,20,100,120), fill='black'); draw.rectangle((20,180,100,250), fill='black')
            path = pathlib.Path(tmp) / 'page.png'; image.save(path)
            tsv = pathlib.Path(tmp) / 'ocr.tsv'; tsv.write_text('')
            self.assertIn([121,180], pixels.ocr(path,tsv)['gaps'])

if __name__ == '__main__': unittest.main()
