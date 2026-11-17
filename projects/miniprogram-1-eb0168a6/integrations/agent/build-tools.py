"""Generate framework-neutral tool schemas from the actual server's exported OpenAPI."""
import json
from pathlib import Path
root = Path(__file__).resolve().parent
spec = json.loads((root / 'openapi.json').read_text())

def resolve(value, stack=()):
    if isinstance(value, list):
        return [resolve(x, stack) for x in value]
    if not isinstance(value, dict):
        return value
    if '$ref' in value:
        ref = value['$ref']
        if ref in stack:
            raise ValueError('Recursive schema requires a dedicated adapter: ' + ref)
        if not ref.startswith('#/'):
            raise ValueError('External refs are not supported')
        target = spec
        for part in ref[2:].split('/'):
            target = target[part.replace('~1', '/').replace('~0', '~')]
        return resolve({**target, **{k:v for k,v in value.items() if k != '$ref'}}, (*stack, ref))
    return {k:resolve(v, stack) for k,v in value.items()}

tools = []
for path, methods in spec['paths'].items():
    for method, operation in methods.items():
        properties, required = {}, []
        for where in ['path', 'query']:
            params = [p for p in operation.get('parameters', []) if p['in'] == where]
            if params:
                required_params = [p['name'] for p in params if p.get('required')]
                properties[where] = {'type':'object', 'properties':{p['name']:resolve(p['schema']) for p in params}, 'additionalProperties':False}
                if required_params:
                    properties[where]['required'] = required_params
                    required.append(where)
        body = operation.get('requestBody', {})
        content = body.get('content', {})
        if 'application/json' in content:
            properties['body'] = resolve(content['application/json']['schema'])
            if body.get('required'):
                required.append('body')
        elif 'multipart/form-data' in content:
            # File bytes never belong in a prompt: adapter resolves a local approved attachment by fileRef.
            properties['fileRef'] = {'type':'string', 'description':'调用方已授权附件的引用，由适配器读取并构造 FormData；不允许任意网络 URL'}
            required.append('fileRef')
            multipart = resolve(content['multipart/form-data']['schema'])
            fields = {k:v for k,v in multipart.get('properties', {}).items() if k != 'file'}
            if fields:
                properties['fields'] = {'type':'object', 'properties':fields, 'additionalProperties':False}
        schema = {'type':'object', 'properties':properties, 'additionalProperties':False}
        if required:
            schema['required'] = required
        tools.append({'name':operation['operationId'], 'description':operation['summary'] + ' ' + operation['description'], 'inputSchema':schema,
                      'annotations':{'readOnlyHint':method == 'get', 'destructiveHint':method != 'get', 'idempotentHint':operation['x-agent']['retry'] in ['read','idempotent']},
                      'x-siyue':{'method':method.upper(), 'path':path, **operation['x-agent']}})
(root / 'tools.json').write_text(json.dumps(tools, ensure_ascii=False, indent=2) + '\n')
print(f'Generated {len(tools)} tool definitions')
