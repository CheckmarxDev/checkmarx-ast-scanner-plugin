package com.checkmarx.jenkins;

import hudson.model.Run;
import jenkins.model.RunAction2;

import javax.annotation.Nonnull;
import java.io.IOException;

public class CheckmarxScanResultsAction implements RunAction2 {

    private transient Run run;

    public CheckmarxScanResultsAction(@Nonnull final Run<?, ?> run) {
        this.run = run;
    }

    public Run getRun() {
        return run;
    }

    @Override
    public void onAttached(final Run<?, ?> run) {
        this.run = run;
    }

    @Override
    public void onLoad(final Run<?, ?> run) {
        this.run = run;
    }

    @Override
    public String getIconFileName() {
        return "/plugin/checkmarx-ast-scanner/images/CxIcon24x24.png";
    }

    @Override
    public String getDisplayName() {
        return "Checkmarx Scan Results";
    }

    @Override
    public String getUrlName() {
        return "scanResults";
    }

    public String getHtmlReport() throws IOException {
        return "html Report for scan";
    }
}

