package scribe.demo.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CheckoutMeta(
    @SerialName("item_count")
    val itemCount: Int,
    @SerialName("subtotal_cents")
    val subtotalCents: Int,
    @SerialName("feature_flag")
    val featureFlag: String,
)

@Serializable
data class SerializationBuyer(
    val id: String,
    val tier: String,
    val email: String,
)

@Serializable
data class SerializationLineItem(
    val sku: String,
    val quantity: Int,
    @SerialName("unit_price_cents")
    val unitPriceCents: Int,
)

@Serializable
data class SerializationPayment(
    val method: String,
    val installments: Int,
    val currency: String,
)

@Serializable
data class SerializationOrderSnapshot(
    @SerialName("order_id")
    val orderId: String,
    val buyer: SerializationBuyer,
    @SerialName("line_items")
    val lineItems: List<SerializationLineItem>,
    val payment: SerializationPayment,
    val tags: List<String>,
    val metadata: Map<String, String>,
)
