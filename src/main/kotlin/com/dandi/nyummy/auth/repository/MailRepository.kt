package com.dandi.nyummy.auth.repository

import com.dandi.nyummy.auth.entity.Mail
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MailRepository : JpaRepository<Mail, Long> {

    fun findByEmail(email: String): Mail?
}
