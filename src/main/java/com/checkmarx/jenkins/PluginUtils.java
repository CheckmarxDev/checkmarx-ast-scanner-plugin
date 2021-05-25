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
        String htmlData = getHtmlText();

        String finalHtmlReport = modifyHeadSection(htmlData);

        workspace.child(workspace.getName() + "_" + CHECKMARX_AST_RESULTS_HTML).write(finalHtmlReport, UTF_8.name());
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

    private static String getHtmlText() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "\n" +
                "<head>\n" +
                "    <meta http-equiv=\"Content-type\" content=\"text/html; charset=utf-8\">\n" +
                "    <meta http-equiv=\"Content-Language\" content=\"en-us\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">\n" +
                "    <title>Snyk test report</title>\n" +
                "    <meta name=\"description\" content=\"18 known vulnerabilities found in 33 vulnerable dependency paths.\">\n" +
                "    <link rel=\"icon\" type=\"image/png\"\n" +
                "        href=\"https://res.cloudinary.com/snyk/image/upload/v1468845142/favicon/favicon.png\" sizes=\"194x194\">\n" +
                "    <link rel=\"shortcut icon\" href=\"https://res.cloudinary.com/snyk/image/upload/v1468845142/favicon/favicon.ico\">\n" +
                "    <script src=\"https://ajax.googleapis.com/ajax/libs/jquery/3.5.1/jquery.min.js\"></script>\n" +
                "    <style type=\"text/css\">\n" +
                "    </style>\n" +
                "    <script>\n" +
                "        $(window).on('load', function() {\n" +
                "            $('.value').each(function() {\n" +
                "                var totalVal = $('#total').text()\n" +
                "                var value = $(this).text();\n" +
                "                var perc = ((value/totalVal) * 100);\n" +
                "                $(this).css('width', perc +\"%\");\n" +
                "            });\n" +
                "        });\n" +
                "        \n" +
                "    </script>\n" +
                "</head>\n" +
                "\n" +
                "<body>\n" +
                "    <div class=\"main\">\n" +
                "        <div class=\"header-row\">\n" +
                "            <div class=\"info\">\n" +
                "                <div class=\"data\">\n" +
                "                    <div class=\"scan-svg\"><svg width=\"40\" height=\"40\" fill=\"none\">\n" +
                "                            <path fill-rule=\"evenodd\" clip-rule=\"evenodd\"\n" +
                "                                d=\"M9.393 32.273c-.65.651-1.713.656-2.296-.057A16.666 16.666 0 1136.583 20h1.75v3.333H22.887a3.333 3.333 0 110-3.333h3.911a7 7 0 10-12.687 5.45c.447.698.464 1.641-.122 2.227-.586.586-1.546.591-2.038-.075A10 10 0 1129.86 20h3.368a13.331 13.331 0 00-18.33-10.652A13.334 13.334 0 009.47 29.846c.564.727.574 1.776-.077 2.427z\"\n" +
                "                                fill=\"url(#scans_svg__paint0_angular)\"></path>\n" +
                "                            <path fill-rule=\"evenodd\" clip-rule=\"evenodd\"\n" +
                "                                d=\"M9.393 32.273c-.65.651-1.713.656-2.296-.057A16.666 16.666 0 1136.583 20h1.75v3.333H22.887a3.333 3.333 0 110-3.333h3.911a7 7 0 10-12.687 5.45c.447.698.464 1.641-.122 2.227-.586.586-1.546.591-2.038-.075A10 10 0 1129.86 20h3.368a13.331 13.331 0 00-18.33-10.652A13.334 13.334 0 009.47 29.846c.564.727.574 1.776-.077 2.427z\"\n" +
                "                                fill=\"url(#scans_svg__paint1_angular)\"></path>\n" +
                "                            <defs>\n" +
                "                                <radialGradient id=\"scans_svg__paint0_angular\" cx=\"0\" cy=\"0\" r=\"1\"\n" +
                "                                    gradientUnits=\"userSpaceOnUse\"\n" +
                "                                    gradientTransform=\"matrix(1 16.50003 -16.50003 1 20 21.5)\">\n" +
                "                                    <stop offset=\"0.807\" stop-color=\"#2991F3\"></stop>\n" +
                "                                    <stop offset=\"1\" stop-color=\"#2991F3\" stop-opacity=\"0\"></stop>\n" +
                "                                </radialGradient>\n" +
                "                                <radialGradient id=\"scans_svg__paint1_angular\" cx=\"0\" cy=\"0\" r=\"1\"\n" +
                "                                    gradientUnits=\"userSpaceOnUse\"\n" +
                "                                    gradientTransform=\"matrix(1 16.50003 -16.50003 1 20 21.5)\">\n" +
                "                                    <stop offset=\"0.807\" stop-color=\"#2991F3\"></stop>\n" +
                "                                    <stop offset=\"1\" stop-color=\"#2991F3\" stop-opacity=\"0\"></stop>\n" +
                "                                </radialGradient>\n" +
                "                            </defs>\n" +
                "                        </svg></div>\n" +
                "                    <div>Scan: d2fed170-d48f-4a81-a4ed-571936b52037</div>\n" +
                "                </div>\n" +
                "                <div class=\"data\">\n" +
                "                    <div class=\"calendar-svg\"><svg width=\"12\" height=\"12\" fill=\"none\">\n" +
                "                            <path fill-rule=\"evenodd\" clip-rule=\"evenodd\"\n" +
                "                                d=\"M3.333 0h1.334v1.333h2.666V0h1.334v1.333h2c.368 0 .666.299.666.667v8.667a.667.667 0 01-.666.666H1.333a.667.667 0 01-.666-.666V2c0-.368.298-.667.666-.667h2V0zm4 2.667V4h1.334V2.667H10V10H2V2.667h1.333V4h1.334V2.667h2.666z\"\n" +
                "                                fill=\"#95939B\"></path>\n" +
                "                        </svg></div>\n" +
                "                    <div>25/05/212021, 07:05:42</div>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "          \n" +
                "        </div>\n" +
                "        <div class=\"top-row\">\n" +
                "            <div class=\"element risk-level-tile high\"><span class=\"value\">High Risk</span></div>\n" +
                "            <div class=\"element\">\n" +
                "                <div class=\"total\">Total Vulnerabilites</div>\n" +
                "                <div>\n" +
                "                    <div class=\"legend\"><span\n" +
                "                            class=\"severity-legend-dot\">high</span>\n" +
                "                        <div class=\"severity-legend-text bg-red\"></div>\n" +
                "                    </div>\n" +
                "                    <div class=\"legend\"><span\n" +
                "                            class=\"severity-legend-dot\">medium</span>\n" +
                "                        <div class=\"severity-legend-text bg-green\"></div>\n" +
                "                    </div>\n" +
                "                    <div class=\"legend\"><span\n" +
                "                            class=\"severity-legend-dot\">low</span>\n" +
                "                        <div class=\"severity-legend-text bg-grey\"></div>\n" +
                "                    </div>\n" +
                "                </div>\n" +
                "                <div class=\"chart\">\n" +
                "                    <div id=\"total\" class=\"total\">3171</div>\n" +
                "                    <div class=\"single-stacked-bar-chart bar-chart\">\n" +
                "                        <div class=\"progress\">\n" +
                "                            <div class=\"progress-bar bg-danger value\" >94</div>\n" +
                "                            <div class=\"progress-bar bg-warning value\">227</div>\n" +
                "                            <div class=\"progress-bar bg-success value\">2977</div>\n" +
                "                        </div>\n" +
                "                    </div>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "            <div class=\"element\">\n" +
                "                <div class=\"total\">Vulnerabilities per Scan Type</div>\n" +
                "                <div class=\"legend\">\n" +
                "                    <div class=\"legend\"><span\n" +
                "                            class=\"engines-legend-dot\">SAST</span>\n" +
                "                        <div class=\"severity-engines-text bg-sast\"></div>\n" +
                "                    </div>\n" +
                "                    <div class=\"legend\"><span\n" +
                "                            class=\"engines-legend-dot\">KICS</span>\n" +
                "                        <div class=\"severity-engines-text bg-kicks\"></div>\n" +
                "                    </div>\n" +
                "                    <div class=\"legend\"><span\n" +
                "                            class=\"engines-legend-dot\">SCA</span>\n" +
                "                        <div class=\"severity-engines-text bg-sca\"></div>\n" +
                "                    </div>\n" +
                "                </div>\n" +
                "                <div class=\"chart\">\n" +
                "                    <div class=\"single-stacked-bar-chart bar-chart\">\n" +
                "                        <div class=\"progress\">\n" +
                "                            <div class=\"progress-bar bg-sast value\">3010</div>\n" +
                "                            <div class=\"progress-bar bg-kicks value\">161</div>\n" +
                "                        </div>\n" +
                "                    </div>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "        \n" +
                "    </div>\n" +
                "</body>";
    }

}
