package com.thanhdatpb.listingguard.api.check;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.thanhdatpb.listingguard.api.listing.Listing;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** SHA-256 over a canonical JSON form of a normalized listing: same content, same hash. */
@Component
public class ContentHasher {

    private final ObjectMapper canonicalMapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    public String hash(Listing listing) {
        JsonNode tree = canonicalMapper.valueToTree(listing);
        if (listing.price() != null) {
            BigDecimal amount = listing.price().amount().stripTrailingZeros();
            ((ObjectNode) tree.get("price")).set("amount", TextNode.valueOf(amount.toPlainString()));
        }
        try {
            byte[] bytes = canonicalMapper.writeValueAsBytes(tree);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("Cannot hash listing", e);
        }
    }
}
