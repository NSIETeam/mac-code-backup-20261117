import definitions from './tools.json' with { type: 'json' };

/** Call connect() first. Supply only approved Blob/File attachments, keyed by opaque fileRef. */
export function agentTools(client, { attachments = new Map() } = {}) {
  return {
    list() { return definitions.filter(tool => client.operations.has(tool.name)); },
    async execute(name, input = {}, options = {}) {
      const definition = definitions.find(tool => tool.name === name && client.operations.has(name));
      if (!definition) throw new Error('操作未授权或不存在');
      const { path, query, body, fields, fileRef } = input;
      let formData;
      if ('fileRef' in definition.inputSchema.properties) {
        const attachment = attachments.get(fileRef);
        if (!attachment || !(attachment.blob instanceof Blob) || !attachment.fileName) throw new Error('附件不存在或未授权');
        formData = new FormData();
        formData.append('file', attachment.blob, attachment.fileName);
        const allowedFields = definition.inputSchema.properties.fields?.properties ?? {};
        for (const [key, value] of Object.entries(fields ?? {})) {
          if (!(key in allowedFields)) throw new Error(`不支持的上传字段 ${key}`);
          formData.append(key, String(value));
        }
      }
      return client.call(name, { path, query, body, formData, signal: options.signal });
    }
  };
}
