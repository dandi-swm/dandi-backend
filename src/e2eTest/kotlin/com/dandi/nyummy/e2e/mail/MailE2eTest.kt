package com.dandi.nyummy.e2e.mail

import com.dandi.nyummy.e2e.support.AuthFixture
import com.dandi.nyummy.e2e.support.ImageFixture
import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ValidatableResponse
import io.restassured.specification.RequestSpecification
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.oneOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailE2eTest {
    // 조회만 하므로 prod에서도 돌린다. 계정에 우편이 없어도 통과해야 하므로 원소 구조와 페이지 나누기는 보지 않는다.
    // 실제 우편이 맨 위에 생기는지는 식사 기록 보상으로 MealE2eTest가 확인한다.
    @Smoke
    @Test
    fun `우편 목록을 조회한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/mails")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("mails", "nextCursor", "hasNext"))
            body("mails", instanceOf<Any>(List::class.java))
        }
    }

    // 우편을 만드는 길이 식사 기록 보상뿐이라 식사를 하나 만들어 그 상자로 확인한다.
    // dev는 하루 기록 제한을 크게 올려 두었다(레포 설정은 5). 5로 돌아가면 MealE2eTest와 합쳐 실행마다 2번을 쓴다.
    //
    // 고정 계정 코인은 실행마다 상자 하나만큼 늘어나고 되돌릴 수 없다. 받은 우편만 삭제할 수 있어 받기를 피할 수 없고,
    // 코인을 쓰는 API가 아직 없으며 어드민 API는 ALB 밖이라 닿지 않는다. 그래서 잔액은 받기 전후 차이로만 검증한다.
    // 데이터를 바꾸므로 prod smoke에서는 돌리지 않는다.
    @Test
    fun `식사 보상 상자를 받고 삭제·복구한다`() {
        val ownerSpec = RequestSpecs.createAuthorizedSpec()
        val otherSpec = RequestSpecs.createAuthorizedSpec(AuthFixture.otherAccessToken)
        val meal = createMeal()
        var mailId: Long? = null

        try {
            // 기록 보상 상자는 식사와 같은 트랜잭션에서 만들어지므로 가장 최근 우편이다.
            // 생성 응답에 우편 id가 없어 등급 번호로 같은 상자인지 확인한다.
            mailId = Given {
                spec(ownerSpec)
                queryParam("size", 1)
            } When {
                get("/api/v1/mails")
            } Then {
                statusCode(200)
                body("mails[0].category", equalTo("REWARD_BOX"))
                body("mails[0].imageId", equalTo(REWARD_BOX_IDS.getValue(meal.rewardBoxCategory)))
                body("mails[0].isClaimed", equalTo(false))
            } Extract {
                jsonPath().getLong("mails[0].mailId")
            }
            // 안 받은 상자가 있으니 홈 우편함 배지가 켜진다. 다른 우편·문의 답변이 있어도 결과는 같다.
            // 꺼지는 경우는 고정 계정에 다른 우편·안 읽은 답변이 남아 있을 수 있어 단위 테스트(HomeServiceTest)가 맡는다.
            assertTrue(getMailboxBadge(ownerSpec), "안 받은 우편이 있는데 홈 우편함 배지가 꺼져 있습니다")
            val coinBefore = getCoin(ownerSpec)
            val otherCoinBefore = getCoin(otherSpec)

            // 1. 안 받은 우편은 삭제할 수 없다. 거부된 뒤에도 목록에 안 받은 채로 남아 있다.
            Given {
                spec(ownerSpec)
            } When {
                delete("/api/v1/mails/$mailId")
            } Then {
                statusCode(409)
                body("code", equalTo("api.mail.notClaimed"))
            }
            assertEquals(false, findMail(mailId)?.get("isClaimed"), "거부된 삭제 뒤 우편 상태가 바뀌었습니다")

            // 2. 남의 우편은 받기·삭제·복구할 수 없다. 우편은 그대로이고 남에게 코인도 가지 않는다.
            Given { spec(otherSpec) } When { post("/api/v1/mails/$mailId/claim") } Then { validateForbidden() }
            Given { spec(otherSpec) } When { delete("/api/v1/mails/$mailId") } Then { validateForbidden() }
            Given { spec(otherSpec) } When { post("/api/v1/mails/$mailId/restore") } Then { validateForbidden() }
            assertEquals(false, findMail(mailId)?.get("isClaimed"), "거부된 요청 뒤 우편 상태가 바뀌었습니다")
            assertEquals(otherCoinBefore, getCoin(otherSpec), "거부된 받기로 다른 사용자의 잔액이 바뀌었습니다")

            // 3. 받기: 상자에 든 코인만큼 홈 잔액이 늘어난다
            val coinQuantity: Int = Given {
                spec(ownerSpec)
            } When {
                post("/api/v1/mails/$mailId/claim")
            } Then {
                statusCode(200)
                body("keySet()", hasItems("coin", "items", "rewardBox"))
                body("coin.quantity", greaterThan(0))
                body("coin.imageId", notNullValue())
                body("rewardBox.imageId", equalTo(REWARD_BOX_IDS.getValue(meal.rewardBoxCategory)))
            } Extract {
                path("coin.quantity")
            }
            assertEquals(coinBefore + coinQuantity, getCoin(ownerSpec), "받은 뒤 홈의 코인 잔액")

            // 4. 다시 받기: 거부되고 코인을 두 번 주지 않는다
            Given {
                spec(ownerSpec)
            } When {
                post("/api/v1/mails/$mailId/claim")
            } Then {
                statusCode(409)
                body("code", equalTo("api.mail.alreadyClaimed"))
            }
            assertEquals(coinBefore + coinQuantity, getCoin(ownerSpec), "거부된 받기 뒤 홈의 코인 잔액")

            // 5. 삭제: 목록에서 빠지고 없는 우편으로 본다. 다시 삭제해도 성공한다.
            Given { spec(ownerSpec) } When { delete("/api/v1/mails/$mailId") } Then { statusCode(204) }
            assertNull(findMail(mailId), "삭제한 우편이 목록에 남아 있습니다")
            Given {
                spec(ownerSpec)
            } When {
                post("/api/v1/mails/$mailId/claim")
            } Then {
                statusCode(404)
                body("code", equalTo("api.mail.notFound"))
            }
            Given { spec(ownerSpec) } When { delete("/api/v1/mails/$mailId") } Then { statusCode(204) }

            // 6. 복구: 받은 상태 그대로 다시 보인다. 다시 복구해도 성공한다.
            Given { spec(ownerSpec) } When { post("/api/v1/mails/$mailId/restore") } Then { statusCode(204) }
            assertEquals(true, findMail(mailId)?.get("isClaimed"), "복구한 우편이 받은 상태로 보이지 않습니다")
            Given { spec(ownerSpec) } When { post("/api/v1/mails/$mailId/restore") } Then { statusCode(204) }
        } finally {
            // 받기 전에 실패했으면 삭제가 거부되므로 받은 뒤 지운다. 이미 받았거나 지웠을 수 있어 결과는 검증하지 않는다.
            mailId?.let {
                Given { spec(ownerSpec) } When { post("/api/v1/mails/$it/claim") }
                Given { spec(ownerSpec) } When { delete("/api/v1/mails/$it") }
            }
            Given { spec(ownerSpec) } When { delete("/api/v1/meals/${meal.id}") }
        }
    }

    // 분석 결과는 보지 않는다. 상자는 분석 전에 식사와 같은 트랜잭션에서 만들어진다.
    private fun createMeal(): CreateMealResponse {
        val imageKey = ImageFixture.createUploadedImage("MEAL", "/images/cake-undated.jpeg", "image/jpeg")

        return Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("imageKey" to imageKey))
        } When {
            post("/api/v1/meals")
        } Then {
            statusCode(200)
            body("rewardBoxCategory", oneOf("BRONZE", "SILVER", "GOLD"))
        } Extract {
            val response = jsonPath()
            CreateMealResponse(id = response.getLong("id"), rewardBoxCategory = response.getString("rewardBoxCategory"))
        }
    }

    // 목록 첫 묶음(최신 10개)에서 찾는다. 방금 만든 우편이라 있다면 첫 묶음에 있다.
    private fun findMail(mailId: Long): Map<String, Any>? = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        get("/api/v1/mails")
    } Then {
        statusCode(200)
    } Extract {
        path("mails.find { it.mailId == $mailId }")
    }

    private fun getCoin(spec: RequestSpecification): Int = Given {
        spec(spec)
    } When {
        get("/api/v1/home")
    } Then {
        statusCode(200)
    } Extract {
        path("user.coin")
    }

    private fun getMailboxBadge(spec: RequestSpecification): Boolean = Given {
        spec(spec)
    } When {
        get("/api/v1/home")
    } Then {
        statusCode(200)
    } Extract {
        path("hasMailboxBadge")
    }

    private fun ValidatableResponse.validateForbidden() {
        statusCode(403)
        body("code", equalTo("api.auth.forbidden"))
    }

    // 식사 생성 API의 응답 중 필요한 것만. 서버 DTO 이름과 맞춘다.
    private data class CreateMealResponse(val id: Long, val rewardBoxCategory: String)

    companion object {
        // 앱이 상자 이미지 경로를 조립하는 번호. 서버의 RewardBoxCategory.id와 같은 약속이다.
        private val REWARD_BOX_IDS = mapOf("BRONZE" to 1, "SILVER" to 2, "GOLD" to 3)
    }
}
