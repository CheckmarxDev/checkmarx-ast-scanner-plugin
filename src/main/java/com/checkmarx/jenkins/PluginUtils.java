package com.checkmarx.jenkins;

import com.checkmarx.ast.*;
import com.checkmarx.jenkins.credentials.CheckmarxApiToken;
import com.checkmarx.jenkins.model.ScanConfig;
import com.checkmarx.jenkins.tools.CheckmarxInstallation;
import hudson.FilePath;
import hudson.model.Run;
import jenkins.model.Jenkins;
import jodd.jerry.Jerry;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static com.cloudbees.plugins.credentials.CredentialsProvider.findCredentialById;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

public class PluginUtils {

    private static final String JENKINS = "Jenkins";
    private static String projectId;
    private static String serverUrl;
    private static final String RESULTS_OVERVIEW_URL = "{serverUrl}/#/projects/{projectId}/overview";
    public static final String CHECKMARX_AST_RESULTS_HTML = "checkmarx-ast-results.html";
    private static final Jerry.JerryParser parser = Objects.requireNonNull(Jerry.jerry());

    public static CheckmarxInstallation findCheckmarxInstallation(final String checkmarxInstallation) {
        final CheckmarxScanBuilder.CheckmarxScanBuilderDescriptor descriptor = Jenkins.get().getDescriptorByType(CheckmarxScanBuilder.CheckmarxScanBuilderDescriptor.class);
        return Stream.of((descriptor).getInstallations())
                .filter(installation -> installation.getName().equals(checkmarxInstallation))
                .findFirst().orElse(null);
    }

    public static CheckmarxApiToken getCheckmarxTokenCredential(final Run<?, ?> run, final String credentialsId) {
        return findCredentialById(credentialsId, CheckmarxApiToken.class, run);
    }

    public static String getSourceDirectory(final FilePath workspace) {
        final File file = new File(workspace.getRemote());
        return file.getAbsolutePath();
    }

    public static boolean submitScanDetailsToWrapper(final ScanConfig scanConfig, final String checkmarxCliExecutable, final CxLoggerAdapter log) throws IOException, InterruptedException, URISyntaxException {

        log.info("Submitting the scan details to the CLI wrapper.");
        final CxScanConfig scan = new CxScanConfig();
        scan.setBaseUri(scanConfig.getServerUrl());

        scan.setAuthType(CxAuthType.TOKEN);
        scan.setApiKey(scanConfig.getCheckmarxToken().getToken().getPlainText());
        scan.setPathToExecutable(checkmarxCliExecutable);
        final CxAuth wrapper = new CxAuth(scan, log);

        final Map<CxParamType, String> params = new HashMap<>();
        params.put(CxParamType.AGENT, PluginUtils.JENKINS);
        params.put(CxParamType.S, scanConfig.getSourceDirectory());
        params.put(CxParamType.TENANT, scanConfig.getTenantName());
//        params.put(CxParamType.V, "");

        params.put(CxParamType.PROJECT_NAME, scanConfig.getProjectName());
        params.put(CxParamType.FILTER, scanConfig.getZipFileFilters());
        params.put(CxParamType.ADDITIONAL_PARAMETERS, scanConfig.getAdditionalOptions());
        params.put(CxParamType.SCAN_TYPES, PluginUtils.getScanType(scanConfig, log));

        final CxScan cxScan = wrapper.cxScanCreate(params);

        if (cxScan != null) {
            PluginUtils.projectId = cxScan.getProjectID();
            PluginUtils.serverUrl = scanConfig.getServerUrl();
            log.info(cxScan.toString());
            log.info("--------------- Checkmarx execution completed ---------------");
            return true;
        }
        return false;
    }

    private static String getScanType(final ScanConfig scanConfig, final CxLoggerAdapter log) {

        String scanType = "";
        final ArrayList<String> scannerList = PluginUtils.getEnabledScannersList(scanConfig, log);

        for (final String item : scannerList) {
            scanType = scanType.concat(item).concat(" ");
        }
        scanType = scanType.trim();
        scanType = scanType.replace(" ", ",");
        return scanType;

    }

    public static ArrayList<String> getEnabledScannersList(final ScanConfig scanConfig, final CxLoggerAdapter log) {

        final ArrayList<String> scannerList = new ArrayList<String>();

        if (scanConfig.isScaEnabled()) {
            scannerList.add(ScanConfig.SCA_SCAN_TYPE);
        }
        if (scanConfig.isSastEnabled()) {
            scannerList.add(ScanConfig.SAST_SCAN_TYPE);
        }
        if (scanConfig.isContainerScanEnabled()) {
            log.warn("Container Scan is not yet supported.");
        }
        if (scanConfig.isKicsEnabled()) {
            scannerList.add(ScanConfig.KICS_SCAN_TYPE);
        }
        return scannerList;
    }

    public static String getCheckmarxResultsOverviewUrl() {

        return String.format(RESULTS_OVERVIEW_URL, serverUrl, projectId);

    }

    public static void generateHTMLReport(FilePath workspace) throws IOException, InterruptedException {
        final String reportName = null;

        //read json
        final InputStream inputStream = PluginUtils.class.getClassLoader().getResourceAsStream("ast-results.json");
        String inputJson = IOUtils.toString(inputStream, StandardCharsets.UTF_8);

        //parse JSON to HTML
        String htmlData = PluginUtils.getHtmlData(inputJson.toString());

        //save HTML report file
        workspace.child(CHECKMARX_AST_RESULTS_HTML).write(htmlData, UTF_8.name());
        String htmlReport = workspace.child("checkmarx-ast-results.html").readToString();
        String modifiedHtmlReport = modifyHeadSection(htmlReport);
        String finalHtmlReport =injectResultsOverviewLink(modifiedHtmlReport, getCheckmarxResultsOverviewUrl());
        workspace.child(CHECKMARX_AST_RESULTS_HTML).write(finalHtmlReport, UTF_8.name());

    }

    public static String modifyHeadSection(@Nonnull String htmlReport) {
        Jerry document = parser.parse(htmlReport);

        document.$("head").append("<link rel=\"stylesheet\" href=\"/jenkins/plugin/checkmarx-ast-scanner/css/report.css\" type=\"text/css\">");
        return document.html();
    }

    public static String injectResultsOverviewLink(@Nonnull String html, @Nullable String overviewLink) {
        if (overviewLink == null || overviewLink.isEmpty()) {
            return html;
        }

        Jerry document = parser.parse(html);
        String monitorHtmlSnippet = format("<center><a target=\"_blank\" href=\"%s\">View On Checkmarx</a></center>", overviewLink);
        // prepend monitor link as first element after body
        document.$("body").prepend(monitorHtmlSnippet);
        return document.html();
    }

    /**
     * Get the JSON data formated in HTML
     */
    public static String getHtmlData( String strJsonData ) {
        return jsonToHtmlTable( new JSONObject( strJsonData ) );
    }

    private static String jsonToHtml(Object obj) {
        StringBuilder html = new StringBuilder( );

        try {
            if (obj instanceof JSONObject) {
                JSONObject jsonObject = (JSONObject)obj;
                String[] keys = JSONObject.getNames( jsonObject );

                html.append("<div class=\"json_object\">");

                if (keys.length > 0) {
                    for (String key : keys) {
                        // print the key and open a DIV
                        html.append("<div><span class=\"json_key\">")
                                .append(key).append("</span> : ");

                        Object val = jsonObject.get(key);
                        // recursive call
                        html.append( jsonToHtml( val ) );
                        // close the div
                        html.append("</div>");
                    }
                }

                html.append("</div>");

            } else if (obj instanceof JSONArray) {
                JSONArray array = (JSONArray)obj;
                for ( int i=0; i < array.length( ); i++) {
                    // recursive call
                    html.append( jsonToHtml( array.get(i) ) );
                }
            } else {
                // print the value
                html.append( obj );
            }
        } catch (JSONException e) { return e.getLocalizedMessage( ) ; }

        return html.toString( );
    }

    private static String jsonToHtmlTable(Object obj) {
        StringBuilder html = new StringBuilder( );

        int countHigh=0;
        int countLow=0;
        int countMedium=0;


        html.append("<!DOCTYPE html>");
        html.append("<html>");
        html.append("<head>");
        html.append("<title>CxASTScans");
        html.append("</title>");

        html.append("</head>");
        html.append("<body>");

        //start table
        html.append("<div class=\"divTable\">");
        html.append("<div class=\"divTableBody\">");

        html.append("<div class=\"divTableRow\">");
        html.append("<div class=\"divTableCell\" >").append("High").append("</div>");
        html.append("<div class=\"divTableCell\" >").append("Medium").append("</div>");
        html.append("<div class=\"divTableCell\" >").append("Low").append("</div>");
        html.append("</div>");

        try {
            if (obj instanceof JSONObject) {
                JSONObject jsonObject = (JSONObject)obj;
                String[] keys = JSONObject.getNames( jsonObject );

              //  html.append("<div class=\"json_object\">");

                if (keys.length > 0) {
                    for (String key : keys) {
                        //check value of key
                        if(key.equals("results")) //going inside "results"
                        {
                            //get the value of "results" (key)
                            Object val = jsonObject.get(key);

                            if (val instanceof JSONArray)
                            {
                                JSONArray array = (JSONArray)val;
                                for ( int i=1; i < array.length( ); i++) { //skipping first item and going to second onwards

                                    if(array.get(i) instanceof JSONObject) //going inside each item
                                    {
                                        JSONObject arrayItem = (JSONObject)array.get(i);
                                        String valType = (String) arrayItem.get("type");
                                        String valSeverity = (String) arrayItem.get("severity");
                                        if(valSeverity.equals("LOW"))
                                        {
                                            countLow++;
                                        }
                                        if(valSeverity.equals("MEDIUM"))
                                        {
                                            countMedium++;
                                        }
                                        if(valSeverity.equals("HIGH"))
                                        {
                                            countHigh++;
                                        }

                                    }
                                }
                            }

                        }

                    }
                }

                //Second row for Values
                html.append("<div class=\"divTableRow\">");
                html.append("<div class=\"divTableCell\">").append(countHigh).append("</div>");
                html.append("<div class=\"divTableCell\">").append(countMedium).append("</div>");
                html.append("<div class=\"divTableCell\">").append(countLow).append("</div>");
                html.append("</div>");

                html.append("</div>"); //end of TableBody
                html.append("</div>"); //end of Table

                html.append("</body>");
                html.append("</html>");

            }
        } catch (JSONException e) { return e.getLocalizedMessage( ) ; }

        return html.toString( );
    }

}
