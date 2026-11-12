package com.dandi.nyummy.config.openapi

import com.dandi.nyummy.header.CommonHeader
import com.dandi.nyummy.header.CurrentCommonHeader
import com.dandi.nyummy.language.CurrentLanguage
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.stereotype.Component

/**
 * 공통 헤더 6종을 모든 API의 선택 헤더로 문서화한다.
 *
 * operation마다 name·schema를 갖춘 Parameter를 붙인다. `$ref`만 둔 Parameter는 name이 null이라
 * 파라미터 이름을 읽는 다른 customizer(EnumOpenApiCustomizer)에서 NPE가 난다.
 * 앱 이전 빌드는 보내지 않으므로 모두 required=false다.
 */
@Component
class CommonHeaderOpenApiCustomizer : OpenApiCustomizer {

    private data class HeaderDocument(val name: String, val description: String, val example: String)

    companion object {
        private val HEADER_DOCUMENTS = listOf(
            HeaderDocument(CommonHeader.APP_VERSION, "앱 버전", "1.2.0"),
            HeaderDocument(CommonHeader.DEVICE_MODEL, "기기 모델명", "iPhone15,2"),
            HeaderDocument(CommonHeader.OS_VERSION, "OS 버전", "iOS 18.1"),
            HeaderDocument(CommonHeader.LANGUAGE, "언어", "ko"),
            HeaderDocument(CommonHeader.REGION, "지역", "KR"),
            HeaderDocument(CommonHeader.TIMEZONE_ID, "타임존 ID", "Asia/Seoul"),
        )
    }

    init {
        // springdoc은 모르는 객체 타입 파라미터를 query 파라미터 묶음으로 펼친다.
        // @CurrentCommonHeader·@CurrentLanguage 값은 위 헤더에서 오므로 파라미터 목록에서 뺀다. (@CurrentUser는 springdoc이 알아서 뺀다)
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentCommonHeader::class.java, CurrentLanguage::class.java)
    }

    override fun customise(openApi: OpenAPI) {
        openApi.paths.orEmpty().values.flatMap { it.readOperations() }.forEach { operation ->
            HEADER_DOCUMENTS.forEach { operation.addParametersItem(it.toParameter()) }
        }
    }

    private fun HeaderDocument.toParameter(): Parameter = Parameter()
        .`in`("header")
        .name(name)
        .required(false)
        .description(description)
        .schema(StringSchema())
        .example(example)
}
