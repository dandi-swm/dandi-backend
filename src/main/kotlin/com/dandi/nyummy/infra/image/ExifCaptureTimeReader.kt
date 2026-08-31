package com.dandi.nyummy.infra.image

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * 이미지 바이트에서 EXIF 촬영 시각을 추출한다.
 *
 * 포맷 판별은 [ImageMetadataReader]가 매직 바이트로 처리하므로 JPEG/PNG를 구분해 호출할 필요가 없다.
 * PNG는 `eXIf` 청크가 있는 경우에만 촬영 시각을 얻을 수 있다.
 */
@Component
class ExifCaptureTimeReader {

    companion object {
        private val DEFAULT_ZONE = ZoneId.of("Asia/Seoul")
        private val logger = LoggerFactory.getLogger(ExifCaptureTimeReader::class.java)
    }

    /**
     * EXIF `DateTimeOriginal`(0x9003)을 읽어 촬영 시각을 반환한다.
     *
     * EXIF 자체에는 타임존이 없으므로, `OffsetTimeOriginal`(0x9011)이 있으면 그 오프셋으로,
     * 없으면 [fallbackZone]으로 해석한다. 즉 오프셋이 없는 이미지의 반환값은 추정치이며,
     * 촬영 지역이 [fallbackZone]과 다르면 실제 촬영 시각과 크게 어긋난다.
     *
     * @param bytes 이미지 전체 바이트.
     * @param fallbackZone EXIF에 `OffsetTimeOriginal`이 없을 때 촬영 시각 해석에 사용할 타임존
     * @return 촬영 시각. 추출할 수 없으면 null
     */
    fun extractCapturedAt(bytes: ByteArray, fallbackZone: ZoneId = DEFAULT_ZONE): Instant? = runCatching {
        ImageMetadataReader
            .readMetadata(ByteArrayInputStream(bytes), bytes.size.toLong())
            .getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            ?.getDateOriginal(TimeZone.getTimeZone(fallbackZone))
            ?.toInstant()
    }.onFailure { logger.warn("EXIF 파싱 실패: size={}bytes", bytes.size, it) }.getOrNull()
}
