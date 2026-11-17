import importlib.util, pathlib, tempfile, unittest
from PIL import Image, ImageDraw
spec=importlib.util.spec_from_file_location('layout',pathlib.Path(__file__).parents[1]/'server'/'ai_handout_images.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class AiImages(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.tmp.name)
    def tearDown(self):self.tmp.cleanup()
    def source(self):
        image=Image.new('RGB',(800,1000),'white');draw=ImageDraw.Draw(image)
        draw.rectangle((60,80,740,180),fill='black');draw.rectangle((60,400,740,530),fill='black')
        path=self.root/'source.png';image.save(path);return path
    def test_single_column_keeps_every_ink_pixel(self):
        result=module.layout(self.source(),{'regions':[{'bounds':[.05,.07,.95,.19]},{'bounds':[.05,.39,.95,.54]}]})
        self.assertEqual(0,result['missingPixels']);self.assertEqual(0,result['cutInk'])
        self.assertGreater(result['regions'][0]['bounds'][1],0);self.assertLess(result['regions'][-1]['bounds'][3],1)
    def test_missing_question_is_never_swallowed_by_first_region(self):
        result=module.layout(self.source(),{'regions':[{'bounds':[.05,.07,.95,.19]}]})
        self.assertGreater(result['missingPixels'],100)
        self.assertLess(result['regions'][0]['bounds'][3],.3)
    def test_multicolumn_does_not_hide_omitted_diagram(self):
        result=module.layout(self.source(),{'regions':[{'bounds':[.05,.07,.45,.19]},{'bounds':[.55,.39,.95,.54]}]})
        self.assertGreater(result['missingPixels'],100)
    def test_cut_through_ink_requires_review(self):
        result=module.layout(self.source(),{'regions':[{'bounds':[0,0,1,.12]},{'bounds':[0,.12,1,1]}]})
        self.assertGreater(result['cutInk'],4)
    def test_preview_honors_crop_and_retains_source_pixels(self):
        source=self.source();result=module.preview({'prefix':str(self.root/'preview'),'items':[{'path':str(source),'bounds':[0,.07,1,.19]}]})
        self.assertTrue(result['half']);self.assertEqual(1,len(result['paths']))
        with Image.open(result['paths'][0]) as preview,Image.open(source) as original:
            self.assertEqual(original.crop((0,70,800,190)).tobytes(),preview.crop((0,0,800,120)).tobytes())

if __name__=='__main__':unittest.main()
