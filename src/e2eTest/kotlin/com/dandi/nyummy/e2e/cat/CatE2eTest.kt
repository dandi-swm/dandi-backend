package com.dandi.nyummy.e2e.cat

import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalToIgnoringCase
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.oneOf
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test

class CatE2eTest {
    @Test
    fun `고양이 정보를 조회한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/cats")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("id", "name", "weight", "weightName", "love", "exp"))
            // 앱은 체형을 enum 이름으로 분기한다. 이름이 바뀌거나 새 값이 생기면 앱 화면이 깨진다.
            body("weight", oneOf("LEAN", "SLIM", "NORMAL", "CHUBBY", "PLUMP"))
        }
    }

    // S3에서 체형별 메타데이터를 읽는 경로라 prod에서는 태스크 롤의 S3 읽기 권한 확인을 겸한다.
    @Smoke
    @Test
    fun `현재 체형의 애니메이션을 조회하고 이미지를 받을 수 있다`() {
        val catWeight: String = Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/cats")
        } Then {
            statusCode(200)
        } Extract {
            path("weight")
        }

        val animation = Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/cats/animations")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("weight", "baseUrl", "animations"))
            // 체형 slug가 어긋나면 다른 체형의 메타데이터를 읽는다(CatAnimationLoader 주석).
            body("weight", equalToIgnoringCase(catWeight))
            body("animations", not(empty<Any>()))
            body("animations[0].keySet()", hasItems("state", "moment", "frame", "animation", "text"))
            body("animations[0].frame.keySet()", hasItems("width", "height", "framesPerRow", "durationMs"))
            body("animations[0].animation[0][0].keySet()", hasItems("src", "frames", "loop"))
        } Extract {
            jsonPath()
        }

        // 앱은 baseUrl 뒤에 src를 그대로 이어붙여 이미지를 받는다. 메타데이터만 맞고 이미지가 없으면 화면이 깨진다.
        // 다른 호스트(S3/CDN)로 가는 요청이라 API용 spec(baseUri, Authorization)을 쓰지 않는다.
        val imageUrl = animation.getString("baseUrl") + animation.getString("animations[0].animation[0][0].src")
        Given {
            this
        } When {
            get(imageUrl)
        } Then {
            statusCode(200)
            contentType(startsWith("image/"))
        }
    }
}
