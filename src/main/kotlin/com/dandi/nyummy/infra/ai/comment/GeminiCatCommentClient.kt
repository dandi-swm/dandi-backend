package com.dandi.nyummy.infra.ai.comment

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.ai.GeminiClient
import com.dandi.nyummy.infra.ai.createGeminiTextPart
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.CaloryShareBalanceResult
import com.dandi.nyummy.meal.dto.CatCommentInput
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MacroNutrient
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * 고양이 코멘트를 Gemini로 쓴다.
 *
 * 많은·부족한 영양소는 서버가 정해서 넘기고([CatCommentInput.caloryShareBalance]), 오늘은 그만 먹으라고 할지는
 * 먹은 양과 목표를 숫자로 주고 모델에 맡긴다. 숫자만 주면 모델은 거의 매번 단백질이 부족하다고 말한다.
 */
@Component
class GeminiCatCommentClient(
    private val geminiClient: GeminiClient,
    private val aiProperties: AiProperties,
    private val objectMapper: ObjectMapper,
) : CatCommentClient {

    companion object {

        // 프롬프트는 언어마다 전문을 따로 둔다. 한국어 지시 끝에 "일본어로 써"만 덧붙였을 때는 지시와 예시의 언어에 끌려
        // 코멘트가 한국어로 나오는 일이 잦았다. 끼워 넣는 줄(음식 줄, 먹은 양, 영양 균형)도 그 언어로 쓴다.
        // 메뉴 추천 후보는 그 언어를 쓰는 사람이 평소에 먹는 음식으로 둔다.

        // 분식은 떡볶이·라면·튀김 위주라 "더 건강한 메뉴" 기준과 맞지 않아 추천 후보에서 뺐다.
        private val KO_PROMPT = CatCommentPrompt(
            template = """
        너는 사용자의 식사를 지켜보는 고양이 "{catName}"야. 방금 기록한 식사에 고양이 말투로 100자 이내의 피드백을 JSON으로 써.

        방금 먹은 식사:
        {mealFoods}

        오늘 그 전에 먹은 식사:
        {earlierMeals}

        오늘 지금까지 먹은 양(방금 먹은 식사 포함)과 하루 목표:
        {intake}

        오늘 지금까지의 영양 균형(서버가 계산했으니 바꾸지 마):
        {balance}

        [쓰는 법]
        먼저 오늘 먹은 칼로리를 하루 목표와 비교해, 아래 1과 2 중 하나로만 써.
        1. 하루 목표에 거의 다 왔거나(대략 90% 이상) 넘었으면: 영양 균형이 어떻든 오늘은 그만 먹으라고 고양이답게 장난스럽게 말해(예: "그만 먹으라냥"). 넘었으면 더 호들갑스럽게. 나무라거나 죄책감을 주지 말고 웃기게 써. 다음 식사 메뉴는 추천하지 마.
        2. 아직 그보다 많이 남았으면: 위 영양 균형을 한 문장으로 짚고(균형이 좋으면 칭찬해), 다음 식사 메뉴를 하나 추천해. 부족한 영양소가 있으면 그것을 채울 메뉴로, 없으면 이 균형을 이어 갈 메뉴로 골라.
           - 배달 앱이나 식당 메뉴판에서 흔히 보는, 누구나 아는 메뉴 이름으로 써. 재료 이름이 아니라 메뉴 이름이어야 해.
           - 쉽게 사 먹거나 집에서 쉽게 만들 수 있는 메뉴로 골라.
           - 같은 조건이면 더 건강한 메뉴를 골라. 채소와 단백질이 함께 들어간 식사를 우선하고, 튀김·가공육·인스턴트 위주의 메뉴는 피해.
           - 그렇다고 닭가슴살·샐러드만 먹는 다이어트 식단은 그 영양소가 정말 부족할 때만 추천해.
           - 이번 추천은 {cuisine} 메뉴 중에서 골라.
           - 오늘 이미 먹은 음식은 추천하지 마.
        - 숫자는 한두 개만 자연스럽게 써도 되고 안 써도 돼.
        - 음식 종류 이름(한식·양식 등)은 말하지 마.
        - 몸·살·배 크기를 놀리지 마.
    """,
            cuisines = listOf("한식", "중식", "일식", "양식"),
            portionUnit = "인분",
            nutrientLabels = mapOf(MacroNutrient.CARBS to "탄", MacroNutrient.PROTEIN to "단", MacroNutrient.FAT to "지"),
            caloryName = "칼로리",
            nutrientNames = mapOf(
                MacroNutrient.CARBS to "탄수화물",
                MacroNutrient.PROTEIN to "단백질",
                MacroNutrient.FAT to "지방",
            ),
            nutrientSeparator = "·",
            targetLabel = "목표",
            firstMealLine = "오늘 첫 식사야.",
            noBalanceLine = "탄수화물·단백질·지방 기록이 없어. 영양 균형은 언급하지 마.",
            balancedLine = "탄수화물·단백질·지방이 목표 비율에 가깝게 균형을 이뤘어.",
            excessLabel = "많은 영양소",
            lackingLabel = "부족한 영양소",
        )

        private val JA_PROMPT = CatCommentPrompt(
            template = """
        あなたはユーザーの食事を見守る猫「{catName}」。いま記録された食事に、猫の口調で100文字以内のフィードバックをJSONで書いて。

        いま食べた食事:
        {mealFoods}

        今日それより前に食べた食事:
        {earlierMeals}

        今日これまでに食べた量(いま食べた食事を含む)と1日の目標:
        {intake}

        今日これまでの栄養バランス(サーバーが計算したものなので変えないで):
        {balance}

        [書き方]
        まず今日食べたカロリーを1日の目標と比べて、下の1と2のどちらか一方だけで書いて。
        1. 1日の目標にほぼ達したか(おおよそ90%以上)超えていれば: 栄養バランスがどうであれ、今日はもう食べないようにと猫らしくおどけて言って(例: 「もう食べちゃだめにゃ」)。超えていればもっと大げさに。責めたり罪悪感を与えたりせず、笑える感じで書いて。次の食事のメニューはすすめないで。
        2. まだそれよりだいぶ残っていれば: 上の栄養バランスに一文で触れて(バランスが良ければほめて)、次の食事のメニューを1つすすめて。不足している栄養素があればそれを補うメニューを、なければこのバランスを続けられるメニューを選んで。
           - デリバリーアプリや飲食店のメニュー表でよく見る、誰でも知っているメニュー名で書いて。食材の名前ではなくメニュー名であること。
           - 手軽に買って食べられるか、家で簡単に作れるメニューを選んで。
           - 同じ条件ならより健康的なメニューを選んで。野菜とたんぱく質が一緒に入った食事を優先し、揚げ物、加工肉、インスタント中心のメニューは避けて。
           - だからといって鶏むね肉やサラダだけのダイエット食は、その栄養素が本当に不足しているときだけすすめて。
           - 今回のおすすめは{cuisine}のメニューから選んで。
           - 今日すでに食べたものはすすめないで。
        - 数字は1つか2つだけ自然に使ってもいいし、使わなくてもいい。
        - 料理ジャンルの名前(和食、洋食など)は言わないで。
        - 体、肉づき、お腹の大きさをからかわないで。

        [出力言語]
        catCommentは日本語の猫の口調で書き、文末を「〜にゃ」で結んで。韓国語を混ぜないで。
        すすめるメニューの名前も日本語で書いて。
    """,
            cuisines = listOf("和食", "中華", "洋食", "韓国料理", "定食"),
            portionUnit = "人前",
            nutrientLabels = mapOf(
                MacroNutrient.CARBS to "炭水化物",
                MacroNutrient.PROTEIN to "たんぱく質",
                MacroNutrient.FAT to "脂質",
            ),
            caloryName = "カロリー",
            nutrientNames = mapOf(
                MacroNutrient.CARBS to "炭水化物",
                MacroNutrient.PROTEIN to "たんぱく質",
                MacroNutrient.FAT to "脂質",
            ),
            nutrientSeparator = "、",
            targetLabel = "目標",
            firstMealLine = "今日最初の食事。",
            noBalanceLine = "炭水化物、たんぱく質、脂質の記録がない。栄養バランスには触れないで。",
            balancedLine = "炭水化物、たんぱく質、脂質が目標の比率に近く、バランスがとれている。",
            excessLabel = "多い栄養素",
            lackingLabel = "不足している栄養素",
        )

        // 영어는 어느 나라 사용자인지 몰라 추천 후보를 넓게 둔다. 같은 내용이 한국어보다 길어 글자 수도 160자로 늘린다.
        private val EN_PROMPT = CatCommentPrompt(
            template = """
        You are "{catName}", a cat who watches over the user's meals. Write feedback on the meal they just logged, in a playful cat voice, within 160 characters, as JSON.

        The meal they just ate:
        {mealFoods}

        Meals they ate earlier today:
        {earlierMeals}

        What they have eaten so far today (including this meal) and the daily target:
        {intake}

        Today's nutrition balance so far (calculated by the server, so do not change it):
        {balance}

        [How to write]
        First compare today's calories with the daily target, then write in exactly one of the two ways below.
        1. If they have almost reached the daily target (roughly 90% or more) or gone over it: whatever the nutrition balance is, playfully tell them, like a cat would, to stop eating for today (e.g. "No more food today, meow!"). Be more dramatic if they went over. Do not scold them or make them feel guilty; keep it funny. Do not recommend a next meal.
        2. If there is still plenty left: mention the nutrition balance above in one sentence (praise it if the balance is good), then recommend one dish for the next meal. If a nutrient is lacking, pick a dish that fills it; if not, pick a dish that keeps this balance going.
           - Use a dish name everyone knows, the kind you see on delivery apps or restaurant menus. It must be the name of a dish, not of an ingredient.
           - Pick something easy to buy or easy to make at home.
           - All else being equal, pick the healthier dish. Prefer meals that have vegetables and protein together, and avoid dishes that are mostly fried food, processed meat, or instant food.
           - Even so, recommend a diet meal of only chicken breast or salad only when that nutrient is really lacking.
           - Pick this recommendation from {cuisine} dishes.
           - Do not recommend something they already ate today.
        - You may use one or two numbers naturally, or none at all.
        - Do not say the name of the cuisine (Korean, Western, and so on).
        - Do not tease them about their body, weight, or belly.

        [Output language]
        Write catComment in English, in a playful cat voice, and work "meow" in naturally. Do not mix in Korean.
        Write the name of the dish you recommend in English too.
    """,
            cuisines = listOf("Korean", "Southeast Asian", "East Asian", "Western", "Mediterranean"),
            portionUnit = " serving(s)",
            nutrientLabels = mapOf(
                MacroNutrient.CARBS to "carbs",
                MacroNutrient.PROTEIN to "protein",
                MacroNutrient.FAT to "fat",
            ),
            caloryName = "Calories",
            nutrientNames = mapOf(
                MacroNutrient.CARBS to "Carbs",
                MacroNutrient.PROTEIN to "Protein",
                MacroNutrient.FAT to "Fat",
            ),
            nutrientSeparator = ", ",
            targetLabel = "target",
            firstMealLine = "This is their first meal today.",
            noBalanceLine = "There is no record of carbs, protein, or fat. Do not mention the nutrition balance.",
            balancedLine = "Carbs, protein, and fat are balanced close to the target ratio.",
            excessLabel = "Too much",
            lackingLabel = "Lacking",
        )

        private val MEAL_AT_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

        private val RESPONSE_SCHEMA = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf("catComment" to mapOf("type" to "STRING")),
            "required" to listOf("catComment"),
        )
    }

    override fun createCatComment(input: CatCommentInput, language: Language, timeout: Duration?): String {
        val resultJson = geminiClient.createContent(
            model = aiProperties.catCommentModel,
            parts = listOf(createGeminiTextPart(createPrompt(input, language))),
            responseSchema = RESPONSE_SCHEMA,
            timeout = timeout,
        )
        val catComment = convertCatCommentResponse(resultJson).catComment.trim()
        if (catComment.isEmpty()) {
            throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "고양이 코멘트가 비어 있습니다.")
        }

        return catComment.take(Meal.CAT_COMMENT_MAX_LENGTH)
    }

    /**
     * 요청 언어의 코멘트 프롬프트에 코멘트 재료를 끼워 넣는다.
     * 다음 식사 추천이 한 메뉴로 몰리지 않게 음식 종류를 요청마다 무작위로 정한다.
     */
    private fun createPrompt(input: CatCommentInput, language: Language): String {
        val prompt = pickCatCommentPrompt(language)

        return prompt.template
            .replace("{catName}", convertSingleLine(input.catName))
            .replace("{mealFoods}", createMealFoodLines(input, prompt))
            .replace("{earlierMeals}", createEarlierMealLines(input, prompt))
            .replace("{intake}", createIntakeLines(input.current, input.target, prompt))
            .replace("{balance}", createBalanceLines(input.caloryShareBalance, prompt))
            .replace("{cuisine}", prompt.cuisines.random())
    }

    // when으로 고르면 언어를 추가할 때 여기서 컴파일이 깨져 빠뜨리지 않는다
    private fun pickCatCommentPrompt(language: Language): CatCommentPrompt = when (language) {
        Language.KO -> KO_PROMPT
        Language.JA -> JA_PROMPT
        Language.EN -> EN_PROMPT
    }

    private fun createMealFoodLines(input: CatCommentInput, prompt: CatCommentPrompt): String =
        input.mealFoods.joinToString("\n") { mealFood ->
            val portion = mealFood.portion.stripTrailingZeros().toPlainString()
            val nutritionText = convertNutritionText(mealFood.nutrition, prompt)
            "- ${convertSingleLine(mealFood.name)} $portion${prompt.portionUnit} ($nutritionText)"
        }

    private fun createEarlierMealLines(input: CatCommentInput, prompt: CatCommentPrompt): String {
        if (input.earlierMeals.isEmpty()) {
            return prompt.firstMealLine
        }

        return input.earlierMeals.joinToString("\n") { meal ->
            val mealAt = meal.mealAt.format(MEAL_AT_FORMATTER)
            "- $mealAt ${convertSingleLine(meal.name)} (${convertNutritionText(meal.nutrition, prompt)})"
        }
    }

    private fun createIntakeLines(current: Nutrition, target: Nutrition, prompt: CatCommentPrompt): String {
        val names = prompt.nutrientNames
        val targetLabel = prompt.targetLabel

        return listOf(
            "- ${prompt.caloryName}: ${current.calory}kcal / $targetLabel ${target.calory}kcal",
            "- ${names.getValue(MacroNutrient.CARBS)}: ${current.carbs}g / $targetLabel ${target.carbs}g",
            "- ${names.getValue(MacroNutrient.PROTEIN)}: ${current.protein}g / $targetLabel ${target.protein}g",
            "- ${names.getValue(MacroNutrient.FAT)}: ${current.fat}g / $targetLabel ${target.fat}g",
        ).joinToString("\n")
    }

    private fun createBalanceLines(caloryShareBalance: CaloryShareBalanceResult?, prompt: CatCommentPrompt): String {
        if (caloryShareBalance == null) {
            return prompt.noBalanceLine
        }
        if (caloryShareBalance.excessNutrients.isEmpty() && caloryShareBalance.lackingNutrients.isEmpty()) {
            return prompt.balancedLine
        }

        return listOfNotNull(
            caloryShareBalance.excessNutrients.takeIf { it.isNotEmpty() }
                ?.let { "- ${prompt.excessLabel}: ${convertNutrientNames(it, prompt)}" },
            caloryShareBalance.lackingNutrients.takeIf { it.isNotEmpty() }
                ?.let { "- ${prompt.lackingLabel}: ${convertNutrientNames(it, prompt)}" },
        ).joinToString("\n")
    }

    private fun convertNutrientNames(nutrients: List<MacroNutrient>, prompt: CatCommentPrompt): String =
        nutrients.joinToString(prompt.nutrientSeparator) { prompt.nutrientNames.getValue(it) }

    private fun convertNutritionText(nutrition: Nutrition, prompt: CatCommentPrompt): String {
        val labels = prompt.nutrientLabels

        return "${nutrition.calory}kcal, ${labels.getValue(MacroNutrient.CARBS)} ${nutrition.carbs}g, " +
            "${labels.getValue(MacroNutrient.PROTEIN)} ${nutrition.protein}g, " +
            "${labels.getValue(MacroNutrient.FAT)} ${nutrition.fat}g"
    }

    // 이름은 사용자가 정하는 글이다. 줄바꿈이 프롬프트의 목록 모양을 깨지 않게 한 줄로 편다.
    private fun convertSingleLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun convertCatCommentResponse(resultJson: String): GeminiCatCommentResponse = try {
        objectMapper.readValue(resultJson, GeminiCatCommentResponse::class.java)
    } catch (e: JacksonException) {
        throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "Gemini 결과를 해석하지 못했습니다.", e)
    }
}

private data class GeminiCatCommentResponse(val catComment: String = "")

/**
 * 한 언어의 코멘트 프롬프트. 지시문 전문과, 거기에 끼워 넣는 줄을 그 언어로 쓰는 데 필요한 문구를 함께 둔다.
 *
 * @property template 지시문 전문. `{catName}`, `{mealFoods}`, `{earlierMeals}`, `{intake}`, `{balance}`, `{cuisine}` 자리에 재료를 끼워 넣는다
 * @property cuisines 다음 식사 추천의 음식 종류 후보. 요청마다 하나를 무작위로 고른다
 * @property portionUnit 인분 숫자 뒤에 붙이는 단위
 * @property nutrientLabels 음식 줄의 영양 값 앞에 붙이는 짧은 이름
 * @property caloryName 먹은 양 줄에 쓰는 칼로리 이름
 * @property nutrientNames 먹은 양 줄과 영양 균형 줄에 쓰는 영양소 이름
 * @property nutrientSeparator 영양 균형 줄에서 영양소 이름을 잇는 글자
 * @property targetLabel 먹은 양 줄에서 하루 목표 앞에 붙이는 말
 * @property firstMealLine 앞선 식사가 없을 때 쓰는 줄
 * @property noBalanceLine 탄단지 기록이 없을 때 쓰는 줄
 * @property balancedLine 많은 것도 부족한 것도 없을 때 쓰는 줄
 * @property excessLabel 많은 영양소 줄의 이름
 * @property lackingLabel 부족한 영양소 줄의 이름
 */
private data class CatCommentPrompt(
    val template: String,
    val cuisines: List<String>,
    val portionUnit: String,
    val nutrientLabels: Map<MacroNutrient, String>,
    val caloryName: String,
    val nutrientNames: Map<MacroNutrient, String>,
    val nutrientSeparator: String,
    val targetLabel: String,
    val firstMealLine: String,
    val noBalanceLine: String,
    val balancedLine: String,
    val excessLabel: String,
    val lackingLabel: String,
)
