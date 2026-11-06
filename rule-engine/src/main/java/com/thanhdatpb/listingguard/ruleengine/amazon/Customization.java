package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.listing.CustomizationSurface;

/**
 * Field paths and the way a surface or an input is named in a message, shared by the customization rules. A path starts at
 * the {@code customization} block of the input schema and counts surfaces, inputs and options in the cleaned lists, from zero,
 * like {@code bullet_points[i]}; {@code []} stands for every surface or input.
 */
final class Customization {

    /** The text inputs of every surface together. */
    static final String TEXT_INPUTS = "customization.surfaces[].text_inputs";

    /** The options of every dropdown of one surface together. */
    static final String DROPDOWN_OPTIONS = "customization.surfaces[].dropdowns[].options";

    /** The image inputs of one surface. */
    static final String IMAGE_INPUTS = "customization.surfaces[].image_inputs";

    private Customization() {
    }

    /** {@code customization.surfaces[i]}. */
    static String surface(int index) {
        return "customization.surfaces[" + index + "]";
    }

    /** {@code customization.surfaces[i].name}, for example {@code dropdowns} or {@code image_inputs}. */
    static String field(int surface, String name) {
        return surface(surface) + "." + name;
    }

    /** {@code customization.surfaces[i].text_inputs[j].name}, for example {@code label} or {@code max_chars}. */
    static String textInput(int surface, int input, String name) {
        return field(surface, "text_inputs") + "[" + input + "]." + name;
    }

    /** {@code customization.surfaces[i].dropdowns[j].options[k]}. */
    static String option(int surface, int dropdown, int option) {
        return field(surface, "dropdowns") + "[" + dropdown + "].options[" + option + "]";
    }

    /** How a message names a surface: by its name, or by its position when it has none. */
    static String describeSurface(int index, CustomizationSurface surface) {
        return surface.name() == null ? "bề mặt in thứ " + (index + 1) : "bề mặt in " + Texts.quote(surface.name());
    }

    /** How a message names an input or a dropdown: by its label, or by its position when it has none. */
    static String describeInput(String kind, int index, String label) {
        return label == null ? kind + " thứ " + (index + 1) : kind + " " + Texts.quote(label);
    }
}
