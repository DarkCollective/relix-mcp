package com.darkcollective.relix.mcp;

import com.darkcollective.relix.embed.Relix;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This server uses the engine only through what the engine publishes.
 *
 * <p>On a module path the compiler would enforce that, but the MCP SDK cannot go on one,
 * so the server builds on the class path, where every package of the engine jar is
 * readable. This test puts the rule back: {@code jdeps} lists every engine package the
 * compiled classes reference, and each must be one the jar's own module descriptor
 * exports. The export list is read from the jar rather than written here, so it cannot
 * go stale when the engine version moves.
 */
class EngineBoundaryTest {

    private static final String OWN = "com.darkcollective.relix.mcp";
    private static final Pattern ENGINE_PACKAGE =
            Pattern.compile("->\\s+(com\\.darkcollective\\.relix(?:\\.[\\w$]+)*)");

    @Test
    void everyEnginePackageUsedIsExported() throws Exception {
        Set<String> exported = exportedPackages();
        Set<String> used = enginePackagesUsedBy(location(Tools.class));

        assertThat(exported).as("the engine jar's exports").contains("com.darkcollective.relix.embed");
        assertThat(used).as("engine packages referenced").isNotEmpty();
        assertThat(used).as("engine packages referenced but not exported")
                .isSubsetOf(exported);
    }

    private static Set<String> exportedPackages() throws URISyntaxException {
        ModuleReference engine = ModuleFinder.of(location(Relix.class)).findAll().stream()
                .findFirst().orElseThrow(() -> new AssertionError("the engine jar is not a module"));
        return engine.descriptor().exports().stream()
                .filter(e -> !e.isQualified())
                .map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> enginePackagesUsedBy(Path classes) {
        ToolProvider jdeps = ToolProvider.findFirst("jdeps")
                .orElseThrow(() -> new AssertionError("jdeps is not available in this JDK"));
        StringWriter out = new StringWriter();
        int status = jdeps.run(new PrintWriter(out), new PrintWriter(out),
                "-verbose:package", "-filter:none", classes.toString());
        assertThat(status).as(out.toString()).isZero();
        Set<String> used = new TreeSet<>();
        Matcher m = ENGINE_PACKAGE.matcher(out.toString());
        while (m.find()) {
            if (!m.group(1).startsWith(OWN)) {
                used.add(m.group(1));
            }
        }
        return used;
    }

    private static Path location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
