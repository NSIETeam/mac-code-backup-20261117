"""Two bounded thread-local ONNX engines. Recognition code stays the pinned original."""
import importlib.util,threading
_init=threading.Lock();_local=threading.local();_module=None

def recognize(node,source,tsv):
    global _module
    with _init:
        if _module is None:
            import rapidocr
            original=rapidocr.RapidOCR
            def cached_engine(*args,**kwargs):
                if not hasattr(_local,'engine'):
                    params=dict(kwargs.get('params',{}));params.update({'Global.font_path':node.c['ocrFont'],'EngineConfig.onnxruntime.intra_op_num_threads':3,'EngineConfig.onnxruntime.inter_op_num_threads':1});kwargs['params']=params
                    _local.engine=original(*args,**kwargs)
                return _local.engine
            rapidocr.RapidOCR=cached_engine
            spec=importlib.util.spec_from_file_location('siyue_pinned_pixels',node.script);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);_module=module
    return _module.rapid(str(source),str(tsv))
