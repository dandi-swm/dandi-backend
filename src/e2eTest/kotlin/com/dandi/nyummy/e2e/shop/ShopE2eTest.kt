package com.dandi.nyummy.e2e.shop

import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ValidatableResponse
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 상점 추천 탭(SDUI)·상품 목록·상단 바. 모두 조회라 prod에서도 돌린다.
class ShopE2eTest {

    @Smoke
    @Test
    fun `추천 탭은 SDUI 칸 목록으로 오고 헤더의 A·B 그룹에 따라 섹션 순서가 바뀐다`() {
        val viewTypesA = getRecommendViewTypes(shopLayoutVariant = null)
        val viewTypesB = getRecommendViewTypes(shopLayoutVariant = "B")

        // A는 오늘의 할인(ItemList)이 인기(Ranking)보다 먼저, B는 반대다. 상품이 없는 섹션은 빠지므로 둘 다 있을 때만 본다.
        if (viewTypesA.containsAll(listOf("ItemList", "Ranking"))) {
            assertTrue(viewTypesA.indexOf("ItemList") < viewTypesA.indexOf("Ranking"), "A 그룹 순서가 다릅니다: $viewTypesA")
            assertTrue(viewTypesB.indexOf("Ranking") < viewTypesB.indexOf("ItemList"), "B 그룹 순서가 다릅니다: $viewTypesB")
        }
    }

    @Smoke
    @Test
    fun `인테리어 탭 상품 목록을 조회한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParams(mapOf("tab" to "INTERIOR", "sort" to "POPULAR"))
        } When {
            get("/api/v1/shop/products")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("items", "nextCursor", "hasNext"))
            body("items.category", everyItem(not(equalTo("BACKGROUND"))))
            body(
                "items[0].keySet()",
                hasItems(
                    "productId",
                    "itemId",
                    "name",
                    "category",
                    "categoryName",
                    "price",
                    "tag",
                    "isLimited",
                    "ownedCount",
                ),
            )
        }
    }

    @Smoke
    @Test
    fun `기간 한정 칩을 고르면 기간 한정 상품만 온다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParams(mapOf("tab" to "INTERIOR", "category" to "LIMITED"))
        } When {
            get("/api/v1/shop/products")
        } Then {
            statusCode(200)
            body("items.isLimited", everyItem(equalTo(true)))
        }
    }

    @Smoke
    @Test
    fun `방 배경 탭에 분류 칩을 보내면 거부한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParams(mapOf("tab" to "BACKGROUND", "category" to "BED"))
        } When {
            get("/api/v1/shop/products")
        } Then {
            validateError(400, "api.common.invalidInputValue")
        }
    }

    @Smoke
    @Test
    fun `상단 바의 잔액과 장바구니 개수를 조회한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/shop/me")
        } Then {
            statusCode(200)
            body("balance", instanceOf<Any>(Int::class.javaObjectType))
            body("cartItemCount", instanceOf<Any>(Int::class.javaObjectType))
        }
    }

    // 배너는 프로모션 하나를 알리고, 링크 끝의 promotionId로 그 프로모션 화면을 연다.
    // 추천 탭 배너는 둥근 모서리, 프로모션 화면 맨 위 배너는 같은 배너를 직각 모서리로 누를 곳 없이 그린다.
    @Smoke
    @Test
    fun `배너의 프로모션 화면을 조회하고 없는 프로모션은 거부한다`() {
        val banners: List<Map<String, Any?>> = Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/shop/pages/recommend")
        } Then {
            statusCode(200)
        } Extract {
            path("sduiComponents.find { it.viewType == 'HeroBanner' }.content.banners") ?: emptyList()
        }

        banners.firstOrNull()?.let { banner ->
            assertTrue(
                banner.keys.containsAll(
                    listOf("bannerId", "imageUrl", "cornerStyle", "title", "description", "action"),
                ),
                "배너 필드가 빠졌습니다: ${banner.keys}",
            )
            assertEquals("ROUNDED", banner["cornerStyle"], "추천 탭 배너는 둥근 모서리여야 합니다")
            @Suppress("UNCHECKED_CAST")
            val navigateLink = (banner.getValue("action") as Map<String, Any?>).getValue("navigateLink") as String
            val promotionId = navigateLink.substringAfterLast("/shop/promotions/")

            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                get("/api/v1/shop/promotions/$promotionId/products")
            } Then {
                statusCode(200)
                body("keySet()", hasItems("banner", "items", "nextCursor", "hasNext"))
                body("banner.bannerId", equalTo(banner["bannerId"]))
                body("banner.cornerStyle", equalTo("SQUARE"))
                body("banner.action", nullValue())
            }
        }

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/shop/promotions/${Long.MAX_VALUE}/products")
        } Then {
            validateError(404, "api.shop.promotionNotFound")
        }
    }

    private fun getRecommendViewTypes(shopLayoutVariant: String?): List<String> = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        if (shopLayoutVariant != null) header("X-Shop-Layout-Variant", shopLayoutVariant) else this
    } When {
        get("/api/v1/shop/pages/recommend")
    } Then {
        statusCode(200)
        body("pageKey", equalTo("RECOMMEND"))
        body("sduiComponents[0].keySet()", hasItems("viewType", "content"))
    } Extract {
        path("sduiComponents.viewType")
    }

    private fun ValidatableResponse.validateError(status: Int, code: String) {
        statusCode(status)
        body("code", equalTo(code))
    }
}
