/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import im.vector.app.R
import im.vector.app.databinding.ViewVerifySessionBannerBinding

class VerifyDeviceBanner @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {
    var delegate: Delegate? = null

    init {
        setupView()
    }

    private fun setupView() {
        inflate(context, R.layout.view_verify_session_banner, this)
        val views = ViewVerifySessionBannerBinding.bind(this)
        views.learnMore.setOnClickListener { delegate?.onVerificationLearnMoreClicked() }
        views.verifyButton.setOnClickListener { delegate?.onVerifySession() }
    }

    interface Delegate {
        fun onVerificationLearnMoreClicked()
        fun onVerifySession()
    }
}
