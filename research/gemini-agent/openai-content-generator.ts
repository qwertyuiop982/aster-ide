// OpenAI transport for Gemini CLI's original ContentGenerator contract.
import { GenerateContentResponse } from '@google/genai';
import { randomUUID } from 'node:crypto';

export class OpenAIContentGenerator {
  constructor(private options: any) {}
  async generateContent(request: any): Promise<any> {
    const messages: any[] = [];
    const text = (value: any): string => typeof value === 'string' ? value : Array.isArray(value) ? value.map(text).join('\n') : value?.text || (value?.parts ? text(value.parts) : '');
    const system = text(request.config?.systemInstruction);
    if (system) messages.push({ role: 'system', content: system });
    for (const content of request.contents || []) {
      if (typeof content === 'string') { messages.push({ role: 'user', content }); continue; }
      const parts = content.parts || [];
      if (parts.some((part: any) => part.inlineData || part.fileData)) throw new Error('OpenAI protocol currently supports text and tool content only');
      const calls = parts.filter((part: any) => part.functionCall).map((part: any) => ({ id: part.functionCall.id || randomUUID(), type: 'function', function: { name: part.functionCall.name, arguments: JSON.stringify(part.functionCall.args || {}) } }));
      const responses = parts.filter((part: any) => part.functionResponse);
      if (responses.length) {
        for (const part of responses) {
          const response = part.functionResponse;
          const previous = [...messages].reverse().flatMap((item: any) => item.tool_calls || []).find((call: any) => call.function.name === response.name && !messages.some((message: any) => message.tool_call_id === call.id));
          const id = response.id || previous?.id;
          if (!id) throw new Error('Unmatched Gemini tool response: ' + response.name);
          messages.push({ role: 'tool', tool_call_id: id, content: JSON.stringify(response.response || {}) });
        }
      }
      const body = parts.filter((part: any) => !part.thought).map((part: any) => part.text || '').join('');
      if (body || calls.length) messages.push({ role: content.role === 'model' ? 'assistant' : 'user', content: body || null, ...(calls.length ? { tool_calls: calls } : {}) });
    }
    const declarations = (request.config?.tools || []).flatMap((tool: any) => tool.functionDeclarations || []);
    const payload: any = { model: request.model, messages, stream: false };
    if (request.config?.maxOutputTokens) payload.max_tokens = request.config.maxOutputTokens;
    const thinking = JSON.parse(process.env.ASTER_GEMINI_OPENAI_THINKING || '{}');
    if (thinking.budgetTokens > 0) throw new Error('OpenAI protocol does not support Gemini thinkingBudget; select automatic or thinking strength');
    if (thinking.level && !['auto', 'off'].includes(thinking.level)) payload.reasoning_effort = thinking.level;
    if (thinking.level === 'off') payload.reasoning_effort = 'none';
    if (request.config?.temperature !== undefined) payload.temperature = request.config.temperature;
    if (request.config?.topP !== undefined) payload.top_p = request.config.topP;
    if (request.config?.stopSequences?.length) payload.stop = request.config.stopSequences;
    if (request.config?.responseMimeType === 'application/json') payload.response_format = { type: 'json_object' };
    const schema = (value: any): any => Array.isArray(value) ? value.map(schema) : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([key, child]) => [key, key === 'type' && typeof child === 'string' ? child.toLowerCase() : schema(child)])) : value;
    if (declarations.length) payload.tools = declarations.map((fn: any) => ({ type: 'function', function: { name: fn.name, description: fn.description, parameters: schema(fn.parametersJsonSchema || fn.parameters || { type: 'object', properties: {} }) } }));
    const base = String(this.options.baseUrl || '').replace(/\/$/, '');
    const endpoint = base.endsWith('/chat/completions') ? base : base + '/chat/completions';
    const controller = new AbortController();
    const signal = request.config?.abortSignal ? AbortSignal.any([request.config.abortSignal, controller.signal]) : controller.signal;
    const timer = setTimeout(() => controller.abort(), 45000);
    try {
      const response = await fetch(endpoint, { method: 'POST', signal, headers: { 'Content-Type': 'application/json', ...(this.options.apiKey ? { Authorization: 'Bearer ' + this.options.apiKey } : {}) }, body: JSON.stringify(payload), redirect: 'error' });
      const reader = response.body?.getReader();
      if (!reader) throw new Error('OpenAI response has no body');
      const chunks: Uint8Array[] = [];
      let bytes = 0;
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          bytes += value.byteLength;
          if (bytes > 4 * 1024 * 1024) { controller.abort(); throw new Error('OpenAI response exceeds 4 MiB'); }
          chunks.push(value);
        }
      } finally { reader.releaseLock(); }
      const raw = Buffer.concat(chunks).toString('utf8');
      if (!response.ok) throw new Error('Gemini OpenAI HTTP ' + response.status + ': ' + raw.slice(0, 1200).split(this.options.apiKey || '\u0000').join('[hidden]'));
      const value = JSON.parse(raw);
      const choice = value.choices?.[0];
      if (!choice?.message) throw new Error('OpenAI response has no message');
      const message = choice.message;
      const parts: any[] = [];
      if (message.content) parts.push({ text: typeof message.content === 'string' ? message.content : text(message.content) });
      for (const call of message.tool_calls || []) parts.push({ functionCall: { id: call.id || randomUUID(), name: call.function.name, args: JSON.parse(call.function.arguments || '{}') } });
      const result = new GenerateContentResponse();
      result.candidates = [{ content: { role: 'model', parts }, index: 0, finishReason: choice.finish_reason === 'length' ? 'MAX_TOKENS' : 'STOP' } as any];
      result.usageMetadata = { promptTokenCount: value.usage?.prompt_tokens || 0, candidatesTokenCount: value.usage?.completion_tokens || 0, totalTokenCount: value.usage?.total_tokens || 0 };
      return result;
    } finally { clearTimeout(timer); }
  }
  async generateContentStream(request: any): Promise<AsyncGenerator<any>> {
    const response = await this.generateContent(request);
    return (async function* () { yield response; })();
  }
  async countTokens(request: any) { return { totalTokens: Math.ceil(JSON.stringify(request.contents || []).length / 3) }; }
  async embedContent(): Promise<any> { throw new Error('Embeddings are unavailable for the OpenAI chat protocol'); }
}
