package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.CustomizationSurface;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.SensitiveKeywords;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A reminder for a text input whose label contains one of the configured keywords (name, number, team, player): a buyer can
 * type a team or a player that is a trademark. It suggests a dropdown whose choices the seller sets, or limiting the kinds of
 * characters. It is the Amazon twin of the Etsy rule {@code personalization_free_text_sensitive} and shares its matching
 * ({@link SensitiveKeywords}). It is a hint and no verdict on trademarks (the trademark check is separate), it has no fix, and
 * it can never be more than INFO: the rule refuses to start when the ruleset rates it higher.
 */
public final class CustomizationFreeTextSensitiveRule extends AmazonRule {

    public static final String ID = "customization_free_text_sensitive";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        SensitiveKeywords.validate(ID, settings);
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        List<String> keywords = settings.stringListParam("keywords");
        List<CustomizationSurface> surfaces = listing.customizationSurfaces();
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < surfaces.size(); i++) {
            CustomizationSurface surface = surfaces.get(i);
            for (int j = 0; j < surface.textInputs().size(); j++) {
                String label = surface.textInputs().get(j).label();
                if (label == null) {
                    continue;
                }
                List<String> found = SensitiveKeywords.found(label, keywords);
                if (!found.isEmpty()) {
                    findings.add(finding(config, settings, Customization.textInput(i, j, "label"), label,
                            "Ô nhập chữ " + Texts.quote(label) + " (" + Customization.describeSurface(i, surface) + ") có từ "
                                    + found.stream().map(Texts::quote).collect(Collectors.joining(", "))
                                    + ": khách có thể nhập tên đội hoặc cầu thủ có nhãn hiệu.",
                            "Cân nhắc dùng dropdown với các lựa chọn do bạn đặt, hoặc giới hạn loại ký tự được nhập "
                                    + "(allowed_char_types), để hạn chế khách nhập tên đội hoặc cầu thủ có nhãn hiệu. Đây chỉ là "
                                    + "gợi ý, không phải kết luận về nhãn hiệu; kết quả kiểm tra nhãn hiệu nằm ở mục riêng."));
                }
            }
        }
        return findings;
    }
}
