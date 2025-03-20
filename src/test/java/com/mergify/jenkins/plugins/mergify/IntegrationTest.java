package com.mergify.jenkins.plugins.mergify;

import static org.mockito.Mockito.verify;

import hudson.ExtensionList;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import java.util.logging.Logger;
import org.junit.BeforeClass;
import org.junit.ClassRule;

public class IntegrationTest {
    private static final Logger LOGGER = Logger.getLogger(IntegrationTest.class.getName());

    @ClassRule
    @ConfiguredWithCode("jcasc-elastic-backend.yml")
    public static JenkinsConfiguredWithCodeRule jenkinsRule = new JenkinsConfiguredWithCodeRule();

    @BeforeClass
    public static void beforeClass() throws Exception {
        LOGGER.info("Jenkins is starting...");
        jenkinsRule.waitUntilNoActivity();
        LOGGER.info("Jenkins started");

        ExtensionList<TracerService> tracerServiceExt =
                jenkinsRule.getInstance().getExtensionList(TracerService.class);
        verify(tracerServiceExt.size() == 1);
        TracerService tracerService = tracerServiceExt.get(0);
    }
}
