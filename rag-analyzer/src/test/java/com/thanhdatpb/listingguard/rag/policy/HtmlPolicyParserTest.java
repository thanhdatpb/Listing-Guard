package com.thanhdatpb.listingguard.rag.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class HtmlPolicyParserTest {

    private final HtmlPolicyParser parser = new HtmlPolicyParser();

    private ParsedPolicy parse(String html) {
        return parser.parse(html.getBytes(StandardCharsets.UTF_8));
    }

    private ParsedPolicy parseArticle(String inner) {
        return parse("<html><body><article class=\"prose prose-article pb-xs-2\">" + inner + "</article></body></html>");
    }

    private static List<String> paths(ParsedPolicy policy) {
        return policy.sections().stream().map(section -> String.join(" > ", section.headingPath())).toList();
    }

    // ---- style 1: real headings, with or without id --------------------------------------------------

    @Test
    void h2WithIdBecomesTheAnchor() {
        ParsedPolicy policy = parseArticle("""
                <p>Before any heading.</p>
                <h2 id="Q1">1. Alcohol</h2>
                <p>Alcohol is prohibited.</p>
                <h2 id="Q2">2. Animal products</h2>
                <p>Certain products are regulated.</p>
                """);

        assertThat(paths(policy)).containsExactly("", "1. Alcohol", "2. Animal products");
        assertThat(policy.sections().get(0).anchor()).isNull();
        assertThat(policy.sections().get(1).anchor()).isEqualTo("Q1");
        assertThat(policy.sections().get(1).units()).containsExactly("Alcohol is prohibited.");
        assertThat(policy.sections().get(2).anchor()).isEqualTo("Q2");
    }

    @Test
    void nestedHeadingsFormAPathAndChildrenInheritTheNearestAnchor() {
        ParsedPolicy policy = parseArticle("""
                <h2 id="Q1">1. Alcohol</h2>
                <p>Intro.</p>
                <h3>More Details:</h3>
                <p>Examples.</p>
                <h3>Examples of What is Allowed:</h3>
                <p>Beer kits.</p>
                <h2 id="Q2">2. Animals</h2>
                <p>Animals.</p>
                """);

        assertThat(paths(policy)).containsExactly(
                "1. Alcohol", "1. Alcohol > More Details:", "1. Alcohol > Examples of What is Allowed:", "2. Animals");
        assertThat(policy.sections().get(1).anchor()).isEqualTo("Q1");
        assertThat(policy.sections().get(3).anchor()).isEqualTo("Q2");
    }

    @Test
    void h3OnlyPagesWithoutIdsWork() {
        ParsedPolicy policy = parseArticle("""
                <h3>1. Etsy's Role</h3>
                <p>Etsy is a marketplace.</p>
                <h3>2. Reports</h3>
                <p>Etsy responds to reports.</p>
                """);

        assertThat(paths(policy)).containsExactly("1. Etsy's Role", "2. Reports");
        assertThat(policy.sections()).allSatisfy(section -> assertThat(section.anchor()).isNull());
    }

    @Test
    void headingThatWrapsStrongUsesItsText() {
        ParsedPolicy policy = parseArticle("<h2 id=\"general-rules\"><strong>General Rules</strong></h2><p>Rules.</p>");

        assertThat(paths(policy)).containsExactly("General Rules");
        assertThat(policy.sections().get(0).anchor()).isEqualTo("general-rules");
    }

    // ---- style 2: strong-led paragraphs as low-level headings under real headings ----------------------

    @Test
    void strongOnlyParagraphIsALowLevelHeadingUnderTheRealHeading() {
        ParsedPolicy policy = parseArticle("""
                <h2 id="selling">1. Selling Basics</h2>
                <p>Keep the basics in mind.</p>
                <p><strong>a. What can be sold on Etsy</strong></p>
                <p>Etsy is unique.</p>
                <ol><li>First item</li><li>Second item</li></ol>
                <p><strong>b. What can't be sold</strong></p>
                <p>Prohibited items are not allowed.</p>
                <h2 id="community">2. Community</h2>
                <p>Be kind.</p>
                """);

        assertThat(paths(policy)).containsExactly(
                "1. Selling Basics",
                "1. Selling Basics > a. What can be sold on Etsy",
                "1. Selling Basics > b. What can't be sold",
                "2. Community");
        assertThat(policy.sections().get(1).units()).containsExactly("Etsy is unique.", "- First item", "- Second item");
        assertThat(policy.sections().get(1).anchor()).isEqualTo("selling");
    }

    @Test
    void textAfterTheStrongInTheSameParagraphIsTheFirstBodyParagraph() {
        ParsedPolicy policy = parseArticle("""
                <h2 id="selling">1. Selling</h2>
                <p><strong>d. Seller Standards</strong> By listing a product you agree to these standards.</p>
                <p>Second paragraph.</p>
                """);

        assertThat(paths(policy)).containsExactly("1. Selling > d. Seller Standards");
        assertThat(policy.sections().get(0).units())
                .containsExactly("By listing a product you agree to these standards.", "Second paragraph.");
    }

    // ---- style 3: no heading tags at all --------------------------------------------------------------

    @Test
    void pageWithoutHeadingTagsUsesStrongLedParagraphs() {
        ParsedPolicy policy = parseArticle("""
                <p>This policy is effective starting on July 29th, 2024.</p>
                <p>Mature content falls into three categories:</p>
                <p><strong>1. Nudity</strong> Most kinds of nudity aren't allowed on Etsy.</p>
                <p>An item is adequately obscured if covered.</p>
                <p><strong>2. Sexual Content</strong> This includes items for sexual activity.</p>
                <ul><li>Wellness items</li><li>Adult toys</li></ul>
                """);

        assertThat(paths(policy)).containsExactly("", "1. Nudity", "2. Sexual Content");
        assertThat(policy.sections().get(0).units()).containsExactly(
                "This policy is effective starting on July 29th, 2024.", "Mature content falls into three categories:");
        assertThat(policy.sections().get(1).units())
                .containsExactly("Most kinds of nudity aren't allowed on Etsy.", "An item is adequately obscured if covered.");
        assertThat(policy.sections().get(2).units())
                .containsExactly("This includes items for sexual activity.", "- Wellness items", "- Adult toys");
    }

    @Test
    void strongTextEndingWithAColonIsNotAHeading() {
        ParsedPolicy policy = parseArticle("""
                <h2 id="Q1">1. Alcohol</h2>
                <p><strong>Note:</strong> This policy was updated on August 11, 2026.</p>
                <p><strong>Resources:</strong> See the FDA guidance.</p>
                """);

        assertThat(paths(policy)).containsExactly("1. Alcohol");
        assertThat(policy.sections().get(0).units())
                .containsExactly("Note: This policy was updated on August 11, 2026.", "Resources: See the FDA guidance.");
    }

    @Test
    void strongLabelFollowedByAColonOutsideTheTagIsNotAHeading() {
        ParsedPolicy policy = parseArticle("""
                <h2 id="Q1">1. Alcohol</h2>
                <p><strong>Note</strong>: This policy was updated on August 11, 2026.</p>
                <p>Alcohol is prohibited.</p>
                <p><strong>Resources</strong>: See the FDA guidance.</p>
                """);

        assertThat(paths(policy)).containsExactly("1. Alcohol");
        assertThat(policy.sections().get(0).units()).containsExactly(
                "Note: This policy was updated on August 11, 2026.", "Alcohol is prohibited.", "Resources: See the FDA guidance.");
    }

    @Test
    void aVeryLongStrongRunIsNotAHeading() {
        String longStrong = "word ".repeat(60);
        ParsedPolicy policy = parseArticle("<h2>Title</h2><p><strong>" + longStrong + "</strong> and more.</p>");

        assertThat(paths(policy)).containsExactly("Title");
    }

    // ---- heading hierarchy of strong-led and plain numbered labels --------------------------------------

    @Test
    void theSellerPolicyCommunicationStandardsStructureNestsUnnumberedHeadingsUnderTheNumberedOneAbove() {
        ParsedPolicy policy = parseArticle("""
                <h2 id="community">2. Being a Member of the Etsy Community</h2>
                <p>Intro to community.</p>
                <p><strong>b. Privacy and Protecting Personal Information</strong></p>
                <p>Privacy text.</p>
                <p><strong>c. Communication Standards</strong></p>
                <p>i. Messages</p>
                <p>With Messages you can talk to buyers.</p>
                <ol><li>Sending unsolicited advertisements</li></ol>
                <p><strong>Interference</strong></p>
                <p>Interference occurs when a member interferes with another shop.</p>
                <p><strong>Harassment and Discrimination</strong></p>
                <p>Harassment of other members is prohibited.</p>
                <p>ii. Forums and Teams</p>
                <p>Forums are public spaces.</p>
                <p>iii. Communicating Cancellations</p>
                <p>Notify the buyer via Messages.</p>
                <h2 id="success">3. Feedback, Cases and Your Success</h2>
                <p><strong>a. Reviews</strong></p>
                <p>Reviews are great.</p>
                """);

        String community = "2. Being a Member of the Etsy Community";
        String communication = community + " > c. Communication Standards";
        assertThat(paths(policy)).containsExactly(
                community,
                community + " > b. Privacy and Protecting Personal Information",
                communication + " > i. Messages",
                communication + " > i. Messages > Interference",
                communication + " > i. Messages > Harassment and Discrimination",
                communication + " > ii. Forums and Teams",
                communication + " > iii. Communicating Cancellations",
                "3. Feedback, Cases and Your Success > a. Reviews");
        assertThat(policy.sections().get(2).units())
                .containsExactly("With Messages you can talk to buyers.", "- Sending unsolicited advertisements");
        assertThat(policy.sections()).allSatisfy(section -> assertThat(section.anchor()).isIn("community", "success"));
    }

    @Test
    void iIsALetterAfterHAndARomanNumeralOtherwise() {
        ParsedPolicy letters = parseArticle("""
                <h2>1. X</h2>
                <p><strong>h. Eighth</strong></p><p>Text h.</p>
                <p><strong>i. Ninth</strong></p><p>Text i.</p>
                <p><strong>j. Tenth</strong></p><p>Text j.</p>
                """);
        ParsedPolicy roman = parseArticle("""
                <h2>1. X</h2>
                <p><strong>c. Third</strong></p>
                <p><strong>i. First part</strong></p><p>Text one.</p>
                <p><strong>ii. Second part</strong></p><p>Text two.</p>
                <p><strong>d. Fourth</strong></p><p>Text four.</p>
                """);

        assertThat(paths(letters)).containsExactly("1. X > h. Eighth", "1. X > i. Ninth", "1. X > j. Tenth");
        assertThat(paths(roman)).containsExactly(
                "1. X > c. Third > i. First part", "1. X > c. Third > ii. Second part", "1. X > d. Fourth");
    }

    @Test
    void anUnnumberedStrongHeadingUnderARealHeadingIsItsChildAndANewRealHeadingClosesIt() {
        ParsedPolicy policy = parseArticle("""
                <h2>1. Rules</h2>
                <p><strong>Interference</strong></p><p>Interference text.</p>
                <h3>Details</h3><p>Detail text.</p>
                """);

        assertThat(paths(policy)).containsExactly("1. Rules > Interference", "1. Rules > Details");
    }

    @Test
    void aRomanLabelMustBeAShortPlainParagraphNotASentence() {
        ParsedPolicy policy = parseArticle("""
                <h2>Rules</h2>
                <p>i. Messages are sent every day by members of the shop.</p>
                <p>i.e. the item must be new</p>
                <p><a href="https://example.com/x">i. Linked label</a></p>
                """);

        assertThat(paths(policy)).containsExactly("Rules");
        assertThat(policy.sections().get(0).units()).hasSize(3);
    }

    // ---- navigation is not content --------------------------------------------------------------------

    @Test
    void listOfInPageLinksIsATableOfContents() {
        ParsedPolicy policy = parseArticle("""
                <ol>
                  <li><a href="https://www.etsy.com/legal/prohibited/?ref=list#Q1">Alcohol and tobacco</a></li>
                  <li><a href="https://www.etsy.com/legal/prohibited/?ref=list#Q2">Animal products</a></li>
                </ol>
                <h2 id="Q1">1. Alcohol and tobacco</h2>
                <p>Prohibited.</p>
                """);

        assertThat(paths(policy)).containsExactly("1. Alcohol and tobacco");
        assertThat(policy.sections().get(0).units()).containsExactly("Prohibited.");
    }

    @Test
    void tableOfContentsWithExtraSubItemLinesIsSkipped() {
        ParsedPolicy policy = parseArticle("""
                <ol>
                  <li><p><a href="https://x/?ref=list#selling">Selling Basics</a><br> a. What can be sold<br> b. Fees</p></li>
                  <li><a href="https://x/?ref=list#success">Feedback</a><br> a. Reviews</li>
                </ol>
                <h2 id="selling">1. Selling Basics</h2>
                <p>Body.</p>
                """);

        assertThat(policy.sections()).hasSize(1);
        assertThat(policy.sections().get(0).units()).containsExactly("Body.");
    }

    @Test
    void runOfStrongItemsSeparatedByLineBreaksIsATableOfContents() {
        ParsedPolicy policy = parseArticle("""
                <p><strong>1. Etsy's Role</strong><br>&nbsp;&nbsp;<strong>2. Reports</strong><br>&nbsp;&nbsp;<strong>3. Counter Notices</strong><br></p>
                <h3>1. Etsy's Role</h3>
                <p>Etsy is a marketplace.</p>
                """);

        assertThat(paths(policy)).containsExactly("1. Etsy's Role");
        assertThat(policy.sections().get(0).units()).containsExactly("Etsy is a marketplace.");
    }

    @Test
    void aListWhoseItemsOnlyContainALinkInTheMiddleIsContent() {
        ParsedPolicy policy = parseArticle("""
                <h2>Rules</h2>
                <ul><li>Read the <a href="https://x/y#z">help article</a> first.</li></ul>
                """);

        assertThat(policy.sections().get(0).units()).containsExactly("- Read the help article first.");
    }

    // ---- noise outside the content ---------------------------------------------------------------------

    @Test
    void onlyTheContentContainersAreRead() {
        ParsedPolicy policy = parse("""
                <html><head><title>Policy</title><script>var secret = 'x';</script><style>p {}</style></head>
                <body>
                  <header><p>Site header</p></header>
                  <div id="gdpr-overlay"><p>We use cookies</p></div>
                  <nav><p>Locale picker</p></nav>
                  <div class="introduction prose"><p>Etsy's mission.</p></div>
                  <article class="prose prose-article"><h2 id="a">A</h2><p>Body text.</p>
                    <figure><img src="x.png"><figcaption>Photo caption</figcaption></figure>
                    <script>alert(1)</script>
                  </article>
                  <footer><p>Site footer</p></footer>
                </body></html>
                """);

        assertThat(policy.sections()).hasSize(2);
        assertThat(policy.sections().get(0).headingPath()).isEmpty();
        assertThat(policy.sections().get(0).units()).containsExactly("Etsy's mission.");
        assertThat(policy.sections().get(1).units()).containsExactly("Body text.");
    }

    @Test
    void fallsBackToMainThenArticleThenBody() {
        assertThat(parse("<html><body><header><p>nav</p></header><main><h2>T</h2><p>In main.</p></main></body></html>")
                .sections().get(0).units()).containsExactly("In main.");
        assertThat(parse("<html><body><p>Plain body.</p></body></html>").sections().get(0).units())
                .containsExactly("Plain body.");
    }

    // ---- last updated ----------------------------------------------------------------------------------

    @Test
    void lastUpdatedLineIsRemovedFromTheContentAndReturnedAsADate() {
        ParsedPolicy policy = parseArticle("""
                <h2 id="a">A</h2><p>Body.</p>
                <p class="text-gray-lighter bt-xs-1">Last updated on Oct 6, 2026</p>
                """);

        assertThat(policy.lastUpdated()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(policy.sections().get(0).units()).containsExactly("Body.");
    }

    @Test
    void fullMonthNamesAndNoLastUpdatedLine() {
        assertThat(parseArticle("<p>Text.</p><p>Last updated on June 15, 2023</p>").lastUpdated())
                .isEqualTo(LocalDate.of(2023, 6, 15));
        assertThat(parseArticle("<p>Text.</p>").lastUpdated()).isNull();
        assertThat(parseArticle("<p>Text.</p><p>Last updated on soon</p>").lastUpdated()).isNull();
    }

    // ---- text handling ---------------------------------------------------------------------------------

    @Test
    void lineBreaksAndNonBreakingSpacesAreNormalized() {
        ParsedPolicy policy = parseArticle("<h2>T</h2><p>First line<br>second&nbsp;line   with   spaces</p>");

        assertThat(policy.sections().get(0).units()).containsExactly("First line\nsecond line with spaces");
    }

    @Test
    void listItemsAreSeparateUnitsAndTablesBecomeRows() {
        ParsedPolicy policy = parseArticle("""
                <h2>T</h2>
                <ul><li>One</li><li>Two</li></ul>
                <table><tr><th>Type</th><th>Limit</th></tr><tr><td>Photo</td><td>10 MB</td></tr></table>
                """);

        assertThat(policy.sections().get(0).units()).containsExactly("- One", "- Two", "Type | Limit", "Photo | 10 MB");
    }

    @Test
    void divsWithBlockChildrenAreWalkedAndPlainDivsAreText() {
        ParsedPolicy policy = parseArticle("""
                <h2>T</h2>
                <div><p>Inside div.</p></div>
                <div>Handcrafted bowl; Hand altered t-shirt</div>
                """);

        assertThat(policy.sections().get(0).units()).containsExactly("Inside div.", "Handcrafted bowl; Hand altered t-shirt");
    }

    @Test
    void emptyAndHeadingOnlyDocumentsHaveNoSections() {
        assertThat(parse("").sections()).isEmpty();
        assertThat(parse("<html><body></body></html>").sections()).isEmpty();
        assertThat(parseArticle("<h2>Only a heading</h2>").sections()).isEmpty();
    }

    @Test
    void emptyHeadingsAreIgnored() {
        ParsedPolicy policy = parseArticle("<h2>A</h2><h3>   </h3><p>Body.</p>");

        assertThat(paths(policy)).containsExactly("A");
    }
}
