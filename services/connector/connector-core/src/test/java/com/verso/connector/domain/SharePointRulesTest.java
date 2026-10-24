package com.verso.connector.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The pure SharePoint rules (ADR-0045): the site as it may reach a Graph path, and paths from the folder tree. */
class SharePointRulesTest {

    private static final String ID = "contoso.sharepoint.com,0f0e0d0c-0b0a-0908-0706-050403020100,00010203-0405-0607-0809-0a0b0c0d0e0f";

    @Test
    void site_whenGivenAsUrlOrId_isNormalisedIntoAGraphPath() {
        SharePointSite hr = SharePointSite.parse("https://Contoso.SharePoint.com/sites/hr/", null, false);
        assertThat(hr.host()).isEqualTo("contoso.sharepoint.com");
        assertThat(hr.url()).isEqualTo("https://contoso.sharepoint.com/sites/hr");
        assertThat(hr.graphPath()).isEqualTo("/sites/contoso.sharepoint.com:/sites/hr");
        assertThat(SharePointSite.parse("https://contoso.sharepoint.com", null, false).graphPath())
                .isEqualTo("/sites/contoso.sharepoint.com");
        assertThat(SharePointSite.parse("https://contoso-my.sharepoint.com/personal/ayse_contoso_com", null, false).host())
                .as("a OneDrive").isEqualTo("contoso-my.sharepoint.com");
        SharePointSite byId = SharePointSite.parse(null, ID, false);
        assertThat(byId.graphPath()).isEqualTo("/sites/" + ID);
        assertThat(byId.url()).isNull();
        assertThat(SharePointSite.parse("https://contoso.sharepoint.com/sites/hr", ID, false).siteId()).isEqualTo(ID);
        assertThat(hr.toString()).doesNotContain("hr");
    }

    @Test
    void site_whenUnsafe_isRefusedWithAFixedWord() {
        for (String url : new String[]{"http://contoso.sharepoint.com/sites/hr", "https://u:p@contoso.sharepoint.com",
                "https://contoso.sharepoint.com/sites/hr?x=1", "https://contoso.sharepoint.com/sites/hr#a",
                "https://contoso.sharepoint.com:8443/sites/hr", "https://contoso.sharepoint.com/sites/../admin",
                "https://contoso.sharepoint.com/sites/a%2Fb", "https://contoso.sharepoint.com/sites/a b", "https://10.0.0.1/x",
                "ftp://contoso.sharepoint.com"}) {
            assertThatThrownBy(() -> SharePointSite.parse(url, null, false)).as(url)
                    .isInstanceOf(IllegalArgumentException.class).message().doesNotContain("contoso");
        }
        assertThatThrownBy(() -> SharePointSite.parse(null, null, false)).hasMessage("SITE_MISSING");
        assertThatThrownBy(() -> SharePointSite.parse(null, "contoso.sharepoint.com/x", false)).hasMessage("SITE_ID_INVALID");
        assertThatThrownBy(() -> SharePointSite.parse("https://fabrikam.sharepoint.com", ID, false)).hasMessage("SITE_MISMATCH");
    }

    @Test
    void tree_whenAskedForAPath_walksToTheRoot_andNoticesRenamesMovesAndGaps() {
        DriveTree tree = new DriveTree("R", Map.of("F1", new DriveTree.Folder("R", "IK"),
                "F2", new DriveTree.Folder("F1", "Politikalar")));
        assertThat(tree.path("F2", "izin.pdf", 32)).isEqualTo("IK/Politikalar/izin.pdf");
        assertThat(tree.path("R", "a.md", 32)).isEqualTo("a.md");
        assertThat(tree.path("F2", "izin.pdf", 1)).as("deeper than the limit").isNull();
        assertThat(tree.path("NOPE", "a.md", 32)).as("unknown parent").isNull();
        assertThat(tree.path("R", "../x", 32)).isEqualTo(DriveTree.UNSAFE);
        assertThat(tree.put("F3", "R", "Yeni")).as("a new folder is no change").isFalse();
        assertThat(tree.put("F2", "F1", "Politikalar")).as("the same folder reported again").isFalse();
        assertThat(tree.put("F2", "F1", "Kurallar")).as("a rename").isTrue();
        assertThat(tree.put("F2", "R", "Kurallar")).as("a move").isTrue();
        assertThat(tree.remove("F1")).isTrue();
        assertThat(tree.remove("F1")).isFalse();
        DriveTree loop = new DriveTree("R", Map.of("A", new DriveTree.Folder("B", "a"), "B", new DriveTree.Folder("A", "b")));
        assertThat(loop.path("A", "x.md", 32)).as("a loop never reaches the root").isNull();
    }
}
