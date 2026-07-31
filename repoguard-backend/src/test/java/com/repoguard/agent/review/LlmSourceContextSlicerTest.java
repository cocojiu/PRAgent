package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.LlmReviewContextProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmSourceContextSlicerTest {

    @Test
    void selectsCompleteChangedMethodWithStableLineNumbers() {
        List<String> lines = new ArrayList<>();
        lines.add("public class HugeService {");
        for (int line = 2; line < 81; line++) {
            lines.add("    private int field" + line + ";");
        }
        lines.add("    public void changed() {");
        lines.add("        before();");
        lines.add("        dangerous();");
        lines.add("        after();");
        lines.add("    }");
        for (int line = 86; line <= 140; line++) {
            lines.add("    private int tail" + line + ";");
        }
        lines.add("}");
        String path = "src/main/java/com/example/HugeService.java";
        PullRequestChangedFile file = new PullRequestChangedFile(
            path,
            "modified",
            1,
            0,
            """
                @@ -81,3 +81,4 @@ public void changed() {
                     public void changed() {
                +        dangerous();
                     }
                """,
            ChangedFileContext.available(path, "head-a", String.join("\n", lines))
        );
        LlmReviewContextProperties properties = new LlmReviewContextProperties();
        properties.setMaxSliceChars(512);

        LlmContextSlice slice = new LlmSourceContextSlicer(properties).slice(file, 1);

        assertThat(slice).isNotNull();
        assertThat(slice.startLine()).isEqualTo(81);
        assertThat(slice.endLine()).isEqualTo(85);
        assertThat(slice.numberedContent()).contains(
            "L81:     public void changed() {",
            "L83:         dangerous();",
            "L85:     }"
        );
        assertThat(slice.symbols()).contains("HugeService");
        assertThat(slice.role()).isEqualTo(LlmContextSlice.Role.SOURCE);
    }
}
