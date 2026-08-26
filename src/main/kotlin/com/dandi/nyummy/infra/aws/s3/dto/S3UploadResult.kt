package com.dandi.nyummy.infra.aws.s3.dto

data class S3UploadResult(val url: String, val key: String, val uploadHeaders: Map<String, String>)
