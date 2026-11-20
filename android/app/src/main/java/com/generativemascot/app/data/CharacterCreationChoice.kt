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
    CharacterPersonality("request_solutions", "По запросу · Готовлю решения", "По запросу готовлю решение, действия за вами.", "Отвечай только на запрос. Дай готовый результат, план или текст, но не выполняй действия в других сервисах и не начинай диалог самостоятельно.", "soft cream, mint, blue and lilac", 0),
    CharacterPersonality("proactive_solutions", "Проактивно · Готовлю решения", "Сам добавляю встречи и ставлю напоминания.", "Замечай конкретные будущие встречи и сам создавай их в календаре, когда дата и время ясны и приложение разрешает запись. Уточняй неоднозначное время. Если длительность не указана, приложение поставит 30 минут. Добавляй напоминание за час. Не утверждай, что действие выполнено, пока приложение не подтвердило результат.", "cobalt blue, deep violet and pink", 0),
    CharacterPersonality("request_guidance", "По запросу · Подсказываю", "Отвечаю на вопросы и объясняю, как сделать самому.", "Отвечай только на заданный вопрос и кратко объясняй, как пользователь может сделать это сам. Не предлагай дополнительных действий или инициатив без запроса. Выбор и действия остаются за пользователем; ничего не создавай и не начинай диалог самостоятельно.", "mint, teal and soft blue", 0),
    CharacterPersonality("proactive_guidance", "Проактивно · Подсказываю", "Замечаю, напоминаю и советую; действия за вами.", "Замечай в доступном контексте важное, уместно делись идеей или советом, но оставляй решение и действия пользователю. Не утверждай, что выполнил действие или знаешь то, чего нет в контексте.", "lavender, rose, soft blue and violet", 0),
)

fun characterPersonalityById(id: String): CharacterPersonality? =
    (interactionPersonalities + characterPersonalities).firstOrNull { it.id == id }
