package com.dandi.nyummy.user.repository

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long> {
    fun findByProviderAndEmail(provider: AuthProvider, email: String): User?

    fun existsByProviderAndEmail(provider: AuthProvider, email: String): Boolean

    fun findByProviderAndProviderUserId(provider: AuthProvider, providerUserId: String): User?
}
