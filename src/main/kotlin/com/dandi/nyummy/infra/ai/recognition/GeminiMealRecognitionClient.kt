package com.dandi.nyummy.infra.ai.recognition

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.ai.GeminiClient
import com.dandi.nyummy.infra.ai.createGeminiImagePart
import com.dandi.nyummy.infra.ai.createGeminiTextPart
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.dto.NutritionHint
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.NetContentUnit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

@Component
class GeminiMealRecognitionClient(
    private val geminiClient: GeminiClient,
    private val aiProperties: AiProperties,
    private val objectMapper: ObjectMapper,
) : MealRecognitionClient {

    companion object {

        // 프롬프트나 응답 스키마를 바꾸면 올린다. 스냅샷에 남겨 사용자가 고친 비율을 프롬프트별로 나눠 본다.
        private const val PROMPT_VERSION = "v4"

        // 영양성분표는 제품으로 판정한다. 값만 남겨 두는 것은 예전에 저장된 행을 읽기 위해서다.
        private val INPUT_CATEGORIES = MealInputCategory.entries - MealInputCategory.NUTRITION_LABEL

        // 1인분 컬럼(DECIMAL(7,2))에 담을 수 있는 가장 큰 값
        private val MAX_NUTRITION_HINT_VALUE = BigDecimal("99999.99")

        private val NUTRITION_HINT_FIELDS = listOf("calory", "carbs", "protein", "fat")

        // 용량 컬럼(DECIMAL(7,1))에 담을 수 있는 가장 큰 값
        private val MAX_NET_CONTENT = BigDecimal("999999.9")

        // 모델이 고를 수 있는 용량 단위와, 저장할 단위·그 단위로 바꾸는 배수. kg과 L는 g과 ml로 바꿔 저장한다.
        private val NET_CONTENT_UNITS = mapOf(
            "G" to (NetContentUnit.G to BigDecimal.ONE),
            "ML" to (NetContentUnit.ML to BigDecimal.ONE),
            "KG" to (NetContentUnit.G to BigDecimal("1000")),
            "L" to (NetContentUnit.ML to BigDecimal("1000")),
        )

        // 프롬프트는 언어마다 전문을 따로 둔다. 한국어 지시 끝에 "일본어로 써"만 덧붙이면 지시와 예시의 언어에 결과가 끌려간다.
        // 규칙을 고칠 때는 세 언어를 같이 고친다.
        private const val KO_PROMPT =
            """
        이 사진에서 사용자가 먹은(또는 먹을) 음식을 알아내 JSON으로 답해.

        [inputCategory] 사진이 무엇을 찍은 것인지 하나를 골라.
        FOOD_PHOTO 실제 음식(먹기 전, 먹는 중, 조금 남은 것)
        PACKAGE 과자·음료·간편식 포장이나 제품, 그 제품의 영양성분표
        TEXT_RECORD 영수증, 주문·배달 앱 화면, 메뉴판, 먹은 것을 적은 글
        ILLUSTRATION 음식 그림이나 모형
        NOT_FOOD 음식과 전혀 관련 없는 사진, 무엇인지 알아볼 수 없는 사진
        음식과 관련이 조금이라도 있으면 NOT_FOOD를 고르지 마.
        여러 개가 함께 보이면 가장 큰 비중을 고르되, 실제 음식이 보이면 FOOD_PHOTO를 골라.

        [foods] 먹은 음식을 하나씩 나눠 적어. NOT_FOOD면 빈 배열이야.
        - 한 항목에는 음식 하나만 적어. 서로 다른 음식은 같은 칸이나 같은 그릇에 있어도 따로 적어(예: 밥과 김치, 밥 옆의 후식).
        - 소스·양념·드레싱은 따로 적지 않고 그 음식에 포함해.
        - name은 음식 이름을 30자 이내로 적어. 포장·제품과 영양성분표는 제품 이름을 적어.
          포장에 적힌 브랜드나 제품명(hasPackageName이 true인 이름)만 다른 언어여도 옮기지 말고 적힌 그대로 적어.
          그 밖의 이름(hasPackageName이 false인 이름)은 반드시 한국어로 적어. 가격표나 라벨에 다른 언어로 적힌 음식 종류도 한국어로 옮겨 적어. (예: 라벨의 焼きいも → 군고구마)
        - 같은 음식이 여러 그릇에 있으면 한 줄로 합쳐 적어.
        - calory·carbs·protein·fat은 사진에 그 음식의 값으로 적혀 있는 숫자만 옮겨 적어. calory는 kcal, 나머지는 g이야.
          음식 옆에 직접 적어 둔 숫자, 메뉴판·주문 화면에 적힌 숫자, 영양성분표의 총 내용량 기준 숫자가 여기에 들어가.
          적혀 있지 않은 항목은 null로 둬. 사진에 적힌 숫자가 없는데 음식만 보고 짐작한 값은 절대 적지 마.
          100g당·100ml당처럼 그 음식 하나 전체의 값이 아닌 숫자는 적지 말고 null로 둬.
          음식이 둘 이상인데 숫자가 음식마다 적혀 있지 않고 한 벌만 적혀 있으면(예: "총 칼로리 650"), 그것은 식사 전체의 값이야.
          식사 전체의 값은 어느 음식에도 적지 말고 모든 음식의 값을 null로 둬. 나눠서 적지도 마.
          음식이 하나뿐이면 적혀 있는 값이 그 음식의 값이니 그대로 적어.
        - netContent·netContentUnit은 PACKAGE일 때만 적어. 포장이나 영양성분표에 적힌 그 제품의 총 내용량이야.
          netContent는 숫자, netContentUnit은 단위(G, ML, KG, L)야. (예: "500ml"는 500과 ML, "1.5L"는 1.5와 L)
          여러 개를 묶어 파는 제품(예: 200ml × 6)은 낱개 하나의 용량을 적어.
          적혀 있지 않거나 단위가 g·ml·kg·L가 아니면(개, 정, 매 등) 둘 다 null로 둬. 짐작한 값은 절대 적지 마.
          용량은 여기에만 적고 name에는 넣지 마. (예: name은 "초코우유", netContent는 200, netContentUnit은 ML)
        - hasPackageName은 name이 포장에 적힌 제품 이름(브랜드나 제품명)이면 true야. PACKAGE일 때만 true가 될 수 있어.
          제품 이름이 사진에 보이지 않아서(예: 영양성분표 면만 찍힌 사진) 그 면의 문구나 음식 종류를 name에 적었으면 false야.
          영양 성분이나 특징을 내세운 문구(예: "나트륨 30% 줄인", "당류 0g")와 음식 종류(예: "컵라면")는 제품 이름이 아니야.
          이 판단과 상관없이 사진에 적힌 영양 값은 위에서 정한 대로 그대로 옮겨 적어.
          (예: "농심 신라면 큰사발"은 true)
        - 이 식사를 대표하는 음식(주메뉴)을 맨 위에 적어.
        - 10개를 넘으면 양이 많은 것부터 10개만 적어.

        [name] 이 식사를 부를 이름을 20자 이내로 적어. NOT_FOOD면 빈 문자열이야.
        - 식사 이름은 항상 한국어로 적어. 제품 이름이 다른 언어로 적혀 있어도 무엇을 먹었는지를 한국어로 적어.
        - 무엇을 먹었는지 알 수 있는 음식 종류로 적어. 브랜드, 제품명, 맛, 용량은 빼.
          (예: "농심 신라면 큰사발" → "컵라면", "서울우유 초코우유와 오리온 초코파이" → "초코우유와 초코파이")
        - 브랜드나 제품명이 없는 음식은 음식 이름 그대로 적어. (예: "닭안심살과 쌀밥")
        - foods의 name은 여기에 맞추지 말고 위에서 정한 대로 구체적인 이름을 그대로 둬.
    """

        // 이름을 쓸 언어는 끝에 따로 적지 않고 name을 정하는 줄에 적는다. 포장에 적힌 제품 이름은 다른 언어여도 적힌 그대로 둔다.
        private const val JA_PROMPT =
            """
        この写真から、ユーザーが食べた(または食べる)食べ物を読み取ってJSONで答えて。

        [inputCategory] 写真が何を写したものか、1つ選んで。
        FOOD_PHOTO 実際の食べ物(食べる前、食べている途中、少し残ったもの)
        PACKAGE お菓子、飲み物、即席食品のパッケージや商品、その商品の栄養成分表示
        TEXT_RECORD レシート、注文やデリバリーアプリの画面、メニュー表、食べたものを書いたメモ
        ILLUSTRATION 食べ物の絵や模型
        NOT_FOOD 食べ物とまったく関係のない写真、何なのか見分けられない写真
        食べ物と少しでも関係があればNOT_FOODを選ばないで。
        複数が一緒に写っていれば最も大きな割合を占めるものを選ぶが、実際の食べ物が写っていればFOOD_PHOTOを選んで。

        [foods] 食べたものを1つずつ分けて書いて。NOT_FOODなら空の配列。
        - 1つの項目には食べ物を1つだけ書いて。別々の食べ物は同じ仕切りや同じ器にあっても分けて書いて(例: ご飯と漬物、ご飯の横のデザート)。
        - ソース、たれ、ドレッシングは分けて書かず、その食べ物に含めて。
        - nameは料理名を30文字以内で書いて。パッケージ、商品と栄養成分表示は商品名を書いて。
          パッケージに書かれたブランドや製品名(hasPackageNameがtrueの名前)だけは、他の言語でも直さず書かれたとおりに書いて。
          それ以外の名前(hasPackageNameがfalseの名前)は必ず日本語で書いて。値札やラベルに他の言語で書かれた食べ物の種類も日本語に直して書いて。(例: ラベルの 군고구마 → 焼きいも)
        - 同じ食べ物が複数の器にあれば1行にまとめて書いて。
        - calory、carbs、protein、fatは、写真にその食べ物の値として書かれている数字だけを写して。caloryはkcal、それ以外はg。
          食べ物の横に直接書いてある数字、メニュー表や注文画面に書かれた数字、栄養成分表示の内容量全体あたりの数字がここに入る。
          書かれていない項目はnullにして。写真に書かれた数字がないのに食べ物を見て推測した値は絶対に書かないで。
          100gあたり、100mlあたりのように、その食べ物1つ全体の値ではない数字は書かずにnullにして。
          食べ物が2つ以上あるのに数字が食べ物ごとに書かれておらず1組だけ書かれていれば(例: 「総カロリー650」)、それは食事全体の値。
          食事全体の値はどの食べ物にも書かず、すべての食べ物の値をnullにして。分けて書くのもだめ。
          食べ物が1つだけなら、書かれている値がその食べ物の値なのでそのまま書いて。
        - netContentとnetContentUnitはPACKAGEのときだけ書いて。パッケージや栄養成分表示に書かれたその商品の内容量。
          netContentは数字、netContentUnitは単位(G, ML, KG, L)。(例: 「500ml」は500とML、「1.5L」は1.5とL)
          複数個をまとめて売る商品(例: 200ml × 6)は1個分の容量を書いて。
          書かれていないか、単位がg、ml、kg、Lでなければ(個、錠、枚など)両方nullにして。推測した値は絶対に書かないで。
          容量はここにだけ書き、nameには入れないで。(例: nameは「チョコレート牛乳」、netContentは200、netContentUnitはML)
        - hasPackageNameは、nameがパッケージに書かれた商品名(ブランドや製品名)ならtrue。PACKAGEのときだけtrueになれる。
          商品名が写真に写っていなくて(例: 栄養成分表示の面だけ写った写真)、その面の文言や食べ物の種類をnameに書いたならfalse。
          栄養成分や特徴をうたう文言(例: 「塩分30%カット」、「糖類0g」)と食べ物の種類(例: 「カップ麺」)は商品名ではない。
          この判断とは関係なく、写真に書かれた栄養の値は上で決めたとおりそのまま写して。
          (例: 「日清カップヌードル ビッグ」はtrue)
        - この食事を代表する食べ物(メイン)をいちばん上に書いて。
        - 10個を超えるなら量の多いものから10個だけ書いて。

        [name] この食事を呼ぶ名前を20文字以内で書いて。NOT_FOODなら空文字列。
        - 食事の名前は必ず日本語で書いて。商品名が他の言語で書かれていても、何を食べたのかを日本語で書いて。
        - 何を食べたのかがわかる料理や食品の名前で書いて。ブランド、製品名、味、容量は除いて。
          (例: 「日清カップヌードル ビッグ」→「カップ麺」、「明治おいしい牛乳とロッテ チョコパイ」→「牛乳とチョコパイ」)
        - 「副菜」「おかず」のようにまとめた呼び方ではなく、料理名を書いて。
        - ブランドや製品名のない食べ物は料理名をそのまま書いて。(例: 「鶏ささみとご飯」)
        - foodsのnameはここに合わせず、上で決めたとおり具体的な名前のままにして。
    """

        // 영어는 같은 이름이 한국어보다 길어 20자를 넘기기 쉽다. 넘으면 서버가 단어 중간에서 자르게 되므로 짧은 이름을 고르게 한다.
        private const val EN_PROMPT =
            """
        Work out the food the user ate (or is about to eat) from this photo and answer in JSON.

        [inputCategory] Choose one for what the photo shows.
        FOOD_PHOTO real food (before eating, while eating, or with a little left)
        PACKAGE the packaging or the product itself for snacks, drinks, or ready meals, or that product's nutrition facts label
        TEXT_RECORD a receipt, an order or delivery app screen, a menu, or a written note of what was eaten
        ILLUSTRATION a drawing or a model of food
        NOT_FOOD a photo that has nothing to do with food, or one where you cannot tell what it is
        If it has anything at all to do with food, do not choose NOT_FOOD.
        If several are shown together, choose the one that takes up the most, but if real food is visible choose FOOD_PHOTO.

        [foods] List what was eaten one food at a time. For NOT_FOOD it is an empty array.
        - Write only one food per item. Write different foods separately even when they share a compartment or a bowl (e.g. rice and kimchi, a dessert next to the rice).
        - Do not list sauces, seasonings, or dressings separately; include them in the food.
        - Write name as the name of the food, within 30 characters. For packaging, products, and nutrition facts labels, write the product name.
          Only a brand or product name written on the package (a name for which hasPackageName is true) is written exactly as it appears, even in another language. Do not translate it.
          Every other name (a name for which hasPackageName is false) must be in English. If the kind of food is written in another language on a price tag or label, write it in English too. (e.g. 焼きいも on a label → Roasted sweet potato)
        - If the same food is in several bowls, combine it into one line.
        - For calory, carbs, protein, and fat, copy only numbers that are written in the photo as that food's values. calory is in kcal and the others are in g.
          Numbers written right next to the food, numbers on a menu or an order screen, and numbers on a nutrition facts label for the whole net content belong here.
          Leave items that are not written as null. Never write a value you guessed from looking at the food when no number is written in the photo.
          Do not write numbers that are not for the whole of that one food, such as per 100g or per 100ml; leave them as null.
          If there are two or more foods and the numbers are written only once rather than per food (e.g. "Total calories 650"), those are the values for the whole meal.
          Do not write whole-meal values on any food; leave every food's values as null. Do not split them up either.
          If there is only one food, the written values are that food's values, so write them as they are.
        - Write netContent and netContentUnit only for PACKAGE. They are the product's total net content written on the package or the nutrition facts label.
          netContent is the number and netContentUnit is the unit (G, ML, KG, L). (e.g. "500ml" is 500 and ML, "1.5L" is 1.5 and L)
          For products sold as a bundle (e.g. 200ml × 6), write the content of a single unit.
          If it is not written, or the unit is not g, ml, kg, or L (pieces, tablets, sheets, and so on), leave both as null. Never write a guessed value.
          Write the content only here and do not put it in name. (e.g. name is "Chocolate milk", netContent is 200, netContentUnit is ML)
        - hasPackageName is true when name is a product name written on the package (a brand or product name). It can be true only for PACKAGE.
          If the product name is not visible in the photo (e.g. a photo of only the nutrition facts side) and you wrote wording from that side or the kind of food as name, it is false.
          Wording that promotes nutrition or features (e.g. "30% less sodium", "0g sugar") and the kind of food (e.g. "Cereal") are not product names.
          Regardless of this decision, copy the nutrition values written in the photo as decided above.
          (e.g. "Kellogg's Corn Flakes Original" is true)
        - Put the food that represents this meal (the main dish) at the top.
        - If there are more than 10, write only the 10 with the largest amounts.

        [name] Write a name for this meal within 20 characters, counting spaces. If it would be longer, choose a shorter name. (e.g. Bulgogi set) For NOT_FOOD it is an empty string.
        - Always write the meal name in English. Even if a product name is written in another language, write what was eaten in English.
        - Write the kind of food that shows what was eaten. Leave out the brand, product name, flavor, and size.
          (e.g. "Kellogg's Corn Flakes Original" → "Cereal", "Hershey's chocolate milk and Oreo cookies" → "Chocolate milk and cookies")
        - For food without a brand or product name, write the name of the food as it is. (e.g. "Chicken breast and rice")
        - Do not match the names in foods to this; keep them specific, as decided above.
    """

        // 모델은 스키마에 적은 순서대로 쓴다. 음식 줄을 먼저 쓰게 해 대표 이름이 그 줄들을 보고 정해지게 한다.
        private val RESPONSE_SCHEMA = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(
                "inputCategory" to mapOf("type" to "STRING", "enum" to INPUT_CATEGORIES.map { it.name }),
                "foods" to mapOf(
                    "type" to "ARRAY",
                    "maxItems" to MealFood.MAX_COUNT,
                    "items" to mapOf(
                        "type" to "OBJECT",
                        // 힌트와 용량은 적혀 있을 때만 오므로 필수가 아니고 null일 수 있다. 이름이 제품명인지는 늘 받는다.
                        "properties" to mapOf("name" to mapOf("type" to "STRING")) +
                            NUTRITION_HINT_FIELDS.associateWith { mapOf("type" to "NUMBER", "nullable" to true) } +
                            mapOf(
                                "hasPackageName" to mapOf("type" to "BOOLEAN"),
                                "netContent" to mapOf("type" to "NUMBER", "nullable" to true),
                                "netContentUnit" to mapOf(
                                    "type" to "STRING",
                                    "enum" to NET_CONTENT_UNITS.keys.toList(),
                                    "nullable" to true,
                                ),
                            ),
                        "required" to listOf("name", "hasPackageName"),
                        // 순서를 정해 두지 않으면 모델이 용량을 건너뛴 뒤 마지막 숫자를 끝없이 이어 쓰는 일이 있었다.
                        // 이름과 그 이름이 제품명인지, 영양성분표 맨 위에 적힌 총 내용량을 먼저 쓰게 하고, 그 뒤에 영양 값을 쓰게 한다.
                        "propertyOrdering" to
                            listOf("name", "hasPackageName", "netContent", "netContentUnit") + NUTRITION_HINT_FIELDS,
                    ),
                ),
                "name" to mapOf("type" to "STRING"),
            ),
            "required" to listOf("inputCategory", "foods", "name"),
            "propertyOrdering" to listOf("inputCategory", "foods", "name"),
        )

        private val logger = LoggerFactory.getLogger(GeminiMealRecognitionClient::class.java)
    }

    override fun recognizeMeal(
        imageBytes: ByteArray,
        contentType: String,
        language: Language,
        timeout: Duration?,
    ): MealRecognitionResult {
        val resultJson = geminiClient.createContent(
            model = aiProperties.recognitionModel,
            parts = listOf(
                createGeminiImagePart(imageBytes, contentType),
                createGeminiTextPart(createPrompt(language)),
            ),
            responseSchema = RESPONSE_SCHEMA,
            timeout = timeout,
        )
        val response = convertRecognitionResponse(resultJson)
        val foods = convertFoods(response.foods, response.inputCategory)

        // 음식 줄이 하나도 없으면 입력 유형이 무엇이든 계산할 것이 없으므로 음식이 아닌 것으로 본다.
        if (response.inputCategory == MealInputCategory.NOT_FOOD || foods.isEmpty()) {
            logger.info(
                "음식이 아닌 사진으로 인식했습니다: inputCategory={}, foodCount={}",
                response.inputCategory,
                foods.size,
            )
            throw AnalysisFailureException(AnalysisFailureCategory.NOT_FOOD, "음식이 아닙니다.")
        }

        return MealRecognitionResult(
            inputCategory = response.inputCategory,
            // 대표 이름이 비어 오면 대표 음식(맨 위 줄)의 이름을 쓴다.
            name = response.name.trim().take(Meal.NAME_MAX_LENGTH).ifEmpty {
                foods.first().name.take(Meal.NAME_MAX_LENGTH)
            },
            foods = foods,
            model = aiProperties.recognitionModel,
            promptVersion = PROMPT_VERSION,
        )
    }

    private fun createPrompt(language: Language): String = when (language) {
        Language.KO -> KO_PROMPT
        Language.JA -> JA_PROMPT
        Language.EN -> EN_PROMPT
    }

    private fun convertRecognitionResponse(resultJson: String): GeminiMealRecognitionResponse = try {
        objectMapper.readValue(resultJson, GeminiMealRecognitionResponse::class.java)
    } catch (e: JacksonException) {
        throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "Gemini 결과를 해석하지 못했습니다.", e)
    }

    /**
     * 모델이 낸 음식 줄을 저장할 수 있는 모양으로 맞춘다. 스키마로 묶어도 어길 수 있어 서버에서 한 번 더 맞춘다.
     * 이름이 빈 줄은 빼고, 이름은 [MealFood.NAME_MAX_LENGTH]자에서 자르고, [MealFood.MAX_COUNT]줄까지만 받는다.
     * 힌트는 저장할 수 있는 값만 남기고([convertNutritionHint]), 용량은 제품 유형일 때만 받는다([convertNetContent]).
     * 이름이 제품명인지도 제품 유형일 때만 받는다. 다른 유형의 음식 이름은 제품명이 아니다.
     */
    private fun convertFoods(
        foods: List<GeminiRecognizedFood>,
        inputCategory: MealInputCategory,
    ): List<MealFoodRecognitionResult> = foods
        .map { it.copy(name = it.name.trim()) }
        .filter { it.name.isNotEmpty() }
        .take(MealFood.MAX_COUNT)
        .map {
            val netContent = if (inputCategory.isPackage) convertNetContent(it) else null

            MealFoodRecognitionResult(
                name = it.name.take(MealFood.NAME_MAX_LENGTH),
                nutritionHint = convertNutritionHint(it),
                netContent = netContent?.first,
                netContentUnit = netContent?.second,
                hasPackageName = inputCategory.isPackage && it.hasPackageName,
            )
        }

    /**
     * 모델이 읽은 용량을 저장할 단위(g, ml)와 자릿수(소수 첫째 자리)로 맞춘다. kg과 L는 1000을 곱해 g과 ml로 바꾼다.
     * 숫자나 단위 하나만 왔거나, 모르는 단위이거나, 0 이하이거나 컬럼에 담을 수 없게 큰 값은 잘못 읽은 것으로 보고 버린다.
     * 용량 때문에 인식 전체를 실패로 돌리지 않는다.
     *
     * @return 용량과 단위. 받을 수 없는 값이면 null
     */
    private fun convertNetContent(food: GeminiRecognizedFood): Pair<BigDecimal, NetContentUnit>? {
        val value = food.netContent ?: return null
        val (netContentUnit, multiplier) = NET_CONTENT_UNITS[food.netContentUnit?.trim()?.uppercase()] ?: return null
        val netContent = (value * multiplier).setScale(1, RoundingMode.HALF_UP)

        return (netContent to netContentUnit).takeIf { netContent > BigDecimal.ZERO && netContent <= MAX_NET_CONTENT }
    }

    /**
     * 모델이 읽은 힌트를 1인분 컬럼에 담을 수 있는 값(소수 둘째 자리)으로 맞춘다.
     * 음수이거나 컬럼에 담을 수 없게 큰 값은 잘못 읽은 것으로 보고 그 항목만 버린다. 숫자 하나 때문에 인식 전체를 실패로 돌리지 않는다.
     *
     * @return 남은 항목이 하나도 없으면 null
     */
    private fun convertNutritionHint(food: GeminiRecognizedFood): NutritionHint? {
        fun convertNutritionHintValue(value: BigDecimal?): BigDecimal? = value
            ?.setScale(2, RoundingMode.HALF_UP)
            ?.takeIf { it >= BigDecimal.ZERO && it <= MAX_NUTRITION_HINT_VALUE }

        val nutritionHint = NutritionHint(
            calory = convertNutritionHintValue(food.calory),
            carbs = convertNutritionHintValue(food.carbs),
            protein = convertNutritionHintValue(food.protein),
            fat = convertNutritionHintValue(food.fat),
        )

        return nutritionHint.takeIf { listOfNotNull(it.calory, it.carbs, it.protein, it.fat).isNotEmpty() }
    }
}

private data class GeminiMealRecognitionResponse(
    val inputCategory: MealInputCategory,
    val foods: List<GeminiRecognizedFood> = emptyList(),
    val name: String = "",
)

private data class GeminiRecognizedFood(
    val name: String,
    val calory: BigDecimal? = null,
    val carbs: BigDecimal? = null,
    val protein: BigDecimal? = null,
    val fat: BigDecimal? = null,
    val hasPackageName: Boolean = false,
    val netContent: BigDecimal? = null,
    // 스키마로 묶어도 모르는 단위가 올 수 있어 문자열로 받는다. enum으로 받으면 단위 하나 때문에 결과 전체를 해석하지 못한다.
    val netContentUnit: String? = null,
)
