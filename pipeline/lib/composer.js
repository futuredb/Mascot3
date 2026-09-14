const Ajv = require("ajv");
const fs = require("node:fs/promises");
const path = require("node:path");
const {
  createOpenRouterDirectBridgeFromEnv,
  createOpenRouterProxyBridgeFromEnv,
} = require("../../extensions/01_bridges/openrouter/bridge.js");

function extractText(response) {
  const content = response.data?.choices?.[0]?.message?.content;
  return Array.isArray(content) ? content.map((part) => part?.text || "").join("\n").trim() : String(content || "").trim();
}

function extractJson(text) {
  const fenced = text.match(/```(?:json)?\s*([\s\S]*?)```/i);
  return JSON.parse((fenced ? fenced[1] : text).trim());
}

function isTextImageModel(model) {
  const modalities = model.architecture?.input_modalities || [];
  return modalities.includes("image") && (model.architecture?.output_modalities || []).includes("text");
}

async function resolveGpt56(bridge, requested) {
  const models = (await bridge.listModels({ timeoutMs: 30000 })).data?.data || [];
  if (requested) {
    const model = models.find((item) => item.id === requested);
    if (!model) throw new Error(`Requested model is not available: ${requested}`);
    return model;
  }
  const gpt56 = models.filter((model) => /^openai\/gpt-5\.6(?:[-:]|$)/i.test(model.id));
  if (!gpt56.length) {
    const openai = models.filter((model) => /^openai\//i.test(model.id)).map((model) => model.id);
    throw new Error(`GPT-5.6 is unavailable. OpenAI models: ${openai.join(", ") || "none"}`);
  }
  return gpt56.find((model) => model.id === "openai/gpt-5.6-terra-pro")
    || gpt56.find((model) => !/:batch$/i.test(model.id) && /-pro$/i.test(model.id))
    || gpt56.find((model) => !/:batch$/i.test(model.id));
}

async function loadValidator(root) {
  const schema = JSON.parse(await fs.readFile(path.join(root, "schema", "lore.schema.json"), "utf8"));
  return new Ajv({ allErrors: true, strict: false }).compile(schema);
}

async function loadLoreContracts(root) {
  const names = ["base_traits", "inner_modifiers", "external_modifiers"];
  const dictionaries = await Promise.all(names.map(async (name) => [
    name,
    JSON.parse(await fs.readFile(path.join(root, "dict", `${name}.json`), "utf8")),
  ]));
  return Object.fromEntries(dictionaries.map(([name, dictionary]) => [
    name,
    {
      instruction: dictionary.instruction,
      selection: dictionary.selection,
      section_instructions: Object.fromEntries(Object.entries(dictionary.sections).map(([section, value]) => [
        section,
        value.instruction || value.axes?.instruction || null,
      ])),
    },
  ]));
}

function createLoreBridge({ env, directOpenRouter = false, fetchImpl } = {}) {
  if (!directOpenRouter) return createOpenRouterProxyBridgeFromEnv({ env, allowDirectFallback: false, fetchImpl });
  if (!String(env.OPENROUTER_API_KEY || "").trim()) throw new Error("--direct-openrouter=yes requires OPENROUTER_API_KEY");
  return createOpenRouterDirectBridgeFromEnv({ env, fetchImpl });
}

async function composeLore({ root, env, primaryData, modelId, runDir, recentSubjects = [], directOpenRouter = false }) {
  const [world, prompt, validate, contracts] = await Promise.all([
    fs.readFile(path.join(root, "world.md"), "utf8"),
    fs.readFile(path.join(root, "prompts", "lore_writer.md"), "utf8"),
    loadValidator(root),
    loadLoreContracts(root),
  ]);
  const bridge = createLoreBridge({ env, directOpenRouter });
  const model = await resolveGpt56(bridge, modelId);
  const subjectHint = recentSubjects.length
    ? `\n\nНЕДАВНО ВСТРЕЧАВШИЕСЯ СУБЪЕКТЫ: ${recentSubjects.join(", ")}. Это не запрет и не реестр: повторы допустимы. Если субъект не закреплён пользователем, предпочти другой естественный вид или тип, когда это не ухудшает персонажа.`
    : "";
  const messages = [
    {
      role: "system",
      content: `${prompt}\n\nФИЗИЧЕСКИЙ КОНТРАКТ МЕЖСЛОЯ (world.md):\n${world}\n\nКОНТРАКТЫ АКТИВНЫХ СЛОВАРЕЙ:\n${JSON.stringify(contracts, null, 2)}${subjectHint}`,
    },
    { role: "user", content: `ПЕРВИЧНЫЕ ДАННЫЕ:\n${JSON.stringify(primaryData, null, 2)}\n\nСХЕМА:\n${JSON.stringify(validate.schema, null, 2)}` },
  ];
  await fs.writeFile(path.join(runDir, "request.json"), `${JSON.stringify({ model: model.id, messages }, null, 2)}\n`);
  let raw = "";
  let result;
  let parsed;
  let errors = [];
  for (let attempt = 0; attempt < 1; attempt += 1) {
    result = await bridge.chatCompletions({ payload: { model: model.id, temperature: 1, max_tokens: 5000, messages }, timeoutMs: 240000 });
    raw = extractText(result);
    try {
      parsed = extractJson(raw);
      if (validate(parsed)) break;
      errors = validate.errors || [];
    } catch (error) {
      errors = [{ message: error.message }];
    }
    messages.push({ role: "user", content: `Исправь ответ: верни только валидный JSON по схеме. Ошибки: ${JSON.stringify(errors)}` });
  }
  await fs.writeFile(path.join(runDir, "raw_response.md"), `${raw}\n`);
  if (!parsed || !validate(parsed)) throw new Error(`Invalid lore schema: ${JSON.stringify(errors)}`);
  return { lore: parsed, model: model.id, transport: result.transportMode || "proxy", requestId: result.requestId || null };
}

module.exports = { composeLore, createLoreBridge, resolveGpt56, isTextImageModel, extractJson, extractText };
