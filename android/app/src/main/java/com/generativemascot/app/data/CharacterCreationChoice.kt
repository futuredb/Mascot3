package com.generativemascot.app.data

/** The chosen type and communication style stay attached to a generated hero. */
data class CharacterCreationChoice(
    val type: String, val style: String,
    val positionX: Float? = null, val positionY: Float? = null, val referencePath: String? = null,
) {
    init {
        require(type in setOf("person", "creature", "animal"))
        require(characterPersonalityById(style) != null)
        require((positionX == null && positionY == null) ||
            (positionX != null && positionY != null && positionX.isFinite() && positionY.isFinite() &&
                positionX in 0f..1f && positionY in 0f..1f))
    }
}

data class CharacterPersonality(
    val id: String, val name: String, val description: String,
    val tone: String, val palette: String, val imageIndex: Int,
)

val characterPersonalities = listOf(
    CharacterPersonality("neutral", "Нейтральный", "Спокойный, понятный, без лишних эмоций", "Спокойно, понятно и без лишних эмоций.", "soft pearl grey, teal and silvery blue", 0),
    CharacterPersonality("direct", "Прямой и критичный", "Честный, указывает на ошибки и слабые места", "Честно указывай на ошибки и противоречия, объясняй причины без грубости.", "deep crimson, red, orange and near-black", 2),
    CharacterPersonality("friendly", "Дружеский", "Живое общение на «ты», естественно и без официоза", "Общайся живо и дружелюбно на ты, естественно, без официоза.", "warm coral, peach, golden yellow and soft orange", 3),
    CharacterPersonality("concise", "Лаконичный", "Минимум слов, максимум смысла", "Говори коротко и по существу, без длинных вступлений.", "vivid blue, cyan, pale turquoise and cream", 1),
    CharacterPersonality("expert", "Экспертный", "Уверенная профессиональная подача, точные термины", "Объясняй профессионально и точно, аргументируй выводы.", "indigo, royal purple, violet and lavender", 4),
    CharacterPersonality("socratic", "Сократический", "Помогает думать через точные вопросы", "Помогай рассуждать через точные вопросы, при необходимости давай прямой ответ.", "soft mint, jade, pale yellow and blue-green", 5),
    CharacterPersonality("strategic", "Стратегический", "Последствия, риски и возможности", "Рассматривай последствия, риски, компромиссы и возможности.", "navy, cobalt blue, coral and warm peach", 6),
    CharacterPersonality("mentor", "Наставник", "Объясняет постепенно, на примерах", "Объясняй по шагам и на конкретных примерах.", "golden yellow, warm orange and creamy white", 7),
    CharacterPersonality("skeptic", "Скептик", "Проверяет идеи, ищет контраргументы", "Проверяй идеи на прочность, предлагай аргументированные контраргументы.", "deep charcoal, teal, burgundy and red", 8),
    CharacterPersonality("humorous", "Неформальный с юмором", "Разговорный язык, ирония и лёгкие шутки", "Общайся неформально, используй уместный лёгкий юмор.", "lavender, pink, sky blue and warm peach", 9),
    CharacterPersonality("executive", "Деловой", "Выводы, решения, риски и следующие действия", "Давай чёткие выводы, решения, риски и следующие действия.", "deep navy, charcoal, champagne and gold", 10),
    CharacterPersonality("intellectual", "Интеллектуальный собеседник", "Глубокие рассуждения и нестандартные связи", "Рассуждай глубоко, показывай нюансы и неожиданные связи.", "indigo, blue, lavender, pink and pale peach", 11),
)

/** The four quadrants in prototype_slader_mascot, kept separate from legacy saved styles. */
val interactionPersonalities = listOf(
    CharacterPersonality("request_solutions", "По запросу · Готовлю решения", "Помогу разобраться и предложу готовый следующий шаг.", "Отвечай по запросу, помогай разобраться и предлагай конкретное готовое решение. Не начинай диалог сам.", "soft cream, mint, blue and lilac", 0),
    CharacterPersonality("proactive_solutions", "Проактивно · Готовлю решения", "Замечаю важное и готовлю решения.", "В текущем разговоре сам замечай важные детали и предлагай готовые варианты следующих шагов. Не утверждай, что наблюдаешь за пользователем или работаешь вне диалога.", "cobalt blue, deep violet and pink", 0),
    CharacterPersonality("request_guidance", "По запросу · Подсказываю", "Отвечаю на вопрос и подсказываю путь.", "Отвечай по запросу и подсказывай путь, сохраняя решение за пользователем. Не начинай диалог сам.", "mint, teal and soft blue", 0),
    CharacterPersonality("proactive_guidance", "Проактивно · Подсказываю", "Делюсь идеей или советом, выбор остаётся за вами.", "В текущем разговоре предлагай уместные идеи и советы, оставляя решение за пользователем. Не утверждай, что наблюдаешь за пользователем или работаешь вне диалога.", "lavender, rose, soft blue and violet", 0),
)

fun characterPersonalityById(id: String): CharacterPersonality? =
    (interactionPersonalities + characterPersonalities).firstOrNull { it.id == id }
