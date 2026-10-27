package com.fishese.moneyfly.recognition

/** Reviewed format structure only: no real merchant, amount, account or notification sample. */
object StarterTemplates {
    private val octopus = listOf("HK$", "HKD").mapIndexed { index, token ->
        TemplateDefinition(
            id = "starter-smart-octopus-$index", name = "Smart Octopus debit ($token)",
            sourcePackage = SuggestedTemplates.SAMSUNG_WALLET,
            patterns = listOf(FieldPattern(MessageField.TITLE, listOf(
                PatternSegment.Literal("Smart Octopus $token"),
                PatternSegment.Slot(SlotName.AMOUNT, 64, numericOnly = true),
                PatternSegment.Literal(" "), PatternSegment.Slot(SlotName.MERCHANT),
            ))), fixedCurrency = "HKD", fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL,
            numberConvention = NumberConvention.DECIMAL_DOT_GROUP_COMMA, enabled = true, revision = 1,
        )
    }

    private val wallet = listOf("HK$", "HKD", "USD", "JPY", "SGD").mapIndexed { index, token ->
        val currency = if (token == "HK$") "HKD" else token
        TemplateDefinition(
            id = "starter-wallet-$index", name = "Wallet payment ($token)", sourcePackage = SuggestedTemplates.GOOGLE_WALLET,
            patterns = listOf(
                FieldPattern(MessageField.TITLE, listOf(PatternSegment.Slot(SlotName.MERCHANT))),
                FieldPattern(MessageField.BODY, listOf(PatternSegment.Literal(token), PatternSegment.Slot(SlotName.AMOUNT, 64),
                    PatternSegment.Literal(" with "), PatternSegment.Slot(SlotName.ACCOUNT_HINT))),
            ), fixedCurrency = currency, fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL,
            numberConvention = NumberConvention.DECIMAL_DOT_GROUP_COMMA, enabled = true, revision = 1,
        )
    }

    private val aeon = listOf("HKD", "USD", "JPY", "SGD").mapIndexed { index, currency ->
        TemplateDefinition(
            id = "starter-aeon-$index", name = "AEON transaction alert ($currency)", sourcePackage = SuggestedTemplates.MESSAGES,
            patterns = listOf(FieldPattern(MessageField.BODY, listOf(
                PatternSegment.Literal("AEON: We received a transaction "), PatternSegment.Slot(SlotName.MERCHANT),
                PatternSegment.Literal(" at amount $currency "), PatternSegment.Slot(SlotName.AMOUNT, 64, numericOnly = true),
                PatternSegment.Literal(" with your credit card "), PatternSegment.Slot(SlotName.ACCOUNT_HINT),
                PatternSegment.Literal(" on "), PatternSegment.Slot(SlotName.VARIABLE, 1024),
            ))), fixedCurrency = currency, fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL,
            numberConvention = NumberConvention.DECIMAL_DOT_GROUP_COMMA, enabled = true, revision = 1,
        )
    }

    val definitions: List<TemplateDefinition> = octopus + wallet + aeon
}
