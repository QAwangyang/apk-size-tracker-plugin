package io.jenkins.plugins.apksize;

import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.Result;
import hudson.tasks.Publisher;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Project-level action: sidebar link "APK Size Trend".
 * Renders chart HTML directly via doIndex() — no jelly dependency.
 *
 * Data is loaded from a persistent JSON file written by ApkSizeDataStore.
 * First access may be slow (scans all historical builds), subsequent loads
 * are sub-millisecond file reads.
 *
 * URL: /job/{project}/apkSizeTrend/ → renders chart page
 * URL: /job/{project}/apkSizeTrend/data → returns JSON
 */
public class ApkSizeTrendAction implements Action {

    private static final Logger LOGGER = Logger.getLogger(ApkSizeTrendAction.class.getName());

    private final Job<?, ?> job;

    public ApkSizeTrendAction(Job<?, ?> job) {
        this.job = job;
        String name = job != null ? job.getFullName() : "null";
        LOGGER.info("ApkSizeTrendAction created for: " + name);
    }

    // ---- Sidebar link ----

    @Override
    public String getIconFileName() {
        return "/plugin/apk-size-tracker/icons/chart-icon.svg";
    }

    @Override
    public String getDisplayName() {
        return "APK Size Trend";
    }

    @Override
    public String getUrlName() {
        return "apkSizeTrend";
    }

    // ---- Main page: doIndex renders full HTML (no jelly needed) ----

    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        rsp.setContentType("text/html;charset=UTF-8");

        String json = buildTrendJson();
        String projectName = job != null ? escapeHtml(job.getDisplayName()) : "Unknown";

        // Inline echarts.min.js — no CDN, no plugin-URL dependency, always works
        String echartsJs = "";
        try (var is = getClass().getResourceAsStream("/js/echarts.min.js")) {
            if (is != null) {
                echartsJs = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to inline echarts: " + e.getMessage());
        }

        var html = new StringBuilder(8192);
        html.append("<!DOCTYPE html><html><head>");
        html.append("<meta charset=\"UTF-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        html.append("<title>APK/IPA Size Trend - ").append(projectName).append("</title>");
        html.append("<style>");
        html.append("*{margin:0;padding:0;box-sizing:border-box}");
        html.append("body{background:#f0f2f5;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif;padding:24px}");
        html.append(".header{background:#fff;border-radius:12px;padding:20px 24px;margin-bottom:16px;box-shadow:0 1px 3px rgba(0,0,0,.08)}");
        html.append(".header h1{font-size:20px;font-weight:600;color:#1a1a2e}");
        html.append(".header .diff-info{font-size:14px;margin-top:6px;padding:8px 12px;border-radius:6px;display:inline-block}");
        html.append(".diff-up{background:#fff0f0;color:#cc3333}");
        html.append(".diff-down{background:#f0fff0;color:#339933}");
        html.append(".diff-same{background:#f8f8f8;color:#888}");
        html.append(".header .meta{font-size:13px;color:#888;margin-top:8px}");
        html.append(".chart-wrap{background:#fff;border-radius:12px;padding:20px;box-shadow:0 1px 3px rgba(0,0,0,.08)}");
        html.append("#chart{width:100%;height:520px}");
        html.append(".loading{text-align:center;padding:80px 20px;color:#999;font-size:15px}");
        html.append(".no-data{text-align:center;padding:80px 20px;color:#999;font-size:16px}");
        html.append(".data-table{background:#fff;border-radius:12px;padding:16px 24px;margin-top:16px;box-shadow:0 1px 3px rgba(0,0,0,.08);overflow-x:auto}");
        html.append(".data-table h3{font-size:14px;font-weight:600;color:#555;margin-bottom:12px}");
        html.append("table{width:100%;border-collapse:collapse;font-size:13px}");
        html.append("th{background:#f8f9fa;padding:10px 12px;text-align:center;border-bottom:2px solid #e8e8e8;font-weight:600;color:#555}");
        html.append("td{padding:8px 12px;text-align:center;border-bottom:1px solid #f0f0f0}");
        html.append("tr:hover{background:#f8f9ff}");
        html.append(".btn-back{display:inline-block;padding:6px 16px;border-radius:6px;background:#e8e8e8;color:#333;text-decoration:none;font-size:13px;margin-right:12px}");
        html.append(".btn-back:hover{background:#ddd}");
        html.append("</style>");
        if (!echartsJs.isEmpty()) {
            html.append("<script>").append(echartsJs).append("</script>");
        }
        html.append("</head><body>");

        // Header
        html.append("<div class=\"header\">");
        html.append("<a class=\"btn-back\" href=\"../\">&larr; Back to Project</a>");
        html.append("<h1>APK/IPA Size Trend</h1>");
        html.append("<div id=\"diffArea\"></div>");
        html.append("<div class=\"meta\" id=\"updateInfo\"></div>");
        html.append("</div>");

        // Chart with loading placeholder
        html.append("<div class=\"chart-wrap\">");
        html.append("<div id=\"noData\" class=\"no-data\" style=\"display:none\">No data yet. Run a build with &quot;Track APK/IPA Size&quot; configured.</div>");
        html.append("<div id=\"loadingMsg\" class=\"loading\">Loading chart...</div>");
        html.append("<div id=\"chart\" style=\"display:none\"></div>");
        html.append("</div>");

        // Data table
        html.append("<div class=\"data-table\">");
        html.append("<h3>Recent Builds (Last 5)</h3>");
        html.append("<div id=\"dataTable\"></div>");
        html.append("</div>");

        // Chart JS
        html.append("<script>");
        html.append("var chartData = ").append(json).append(";\n");
        html.append("function initChart(){");
        html.append("document.getElementById('loadingMsg').style.display='none';\n");
        html.append("renderChart(chartData);\n");
        html.append("}\n");

        html.append("function renderChart(data){\n");
        html.append("var allBns=data.allBuildNumbers||[];\n");
        html.append("if(!allBns.length){document.getElementById('noData').style.display='block';return;}\n");
        html.append("var hasApk=data.apk&&data.apk.hasData;\n");
        html.append("var hasIpa=data.ipa&&data.ipa.hasData;\n");
        html.append("var hasHap=data.hap&&data.hap.hasData;\n");
        html.append("document.getElementById('updateInfo').textContent='Last updated: '+(data.lastUpdated||'-');\n");
        html.append("if(!hasApk&&!hasIpa&&!hasHap){\n");
        html.append("document.getElementById('noData').style.display='block';");
        html.append("document.getElementById('chart').style.display='none';return;}\n");

        // Diff banner (new format: no latestBN/prevBN)
        html.append("var diffHtml='';\n");
        html.append("if(data.diff&&data.diff.apk){var d=data.diff.apk;");
        html.append("if(d.diffMb>0)diffHtml+='<span class=\"diff-info diff-up\">APK: +'+d.diffMb.toFixed(2)+' MB</span> ';");
        html.append("else if(d.diffMb<0)diffHtml+='<span class=\"diff-info diff-down\">APK: '+d.diffMb.toFixed(2)+' MB</span> ';");
        html.append("else diffHtml+='<span class=\"diff-info diff-same\">APK: no change</span> ';");
        html.append("}\n");
        html.append("if(data.diff&&data.diff.ipa){var d=data.diff.ipa;");
        html.append("if(d.diffMb>0)diffHtml+='<span class=\"diff-info diff-up\">IPA: +'+d.diffMb.toFixed(2)+' MB</span> ';");
        html.append("else if(d.diffMb<0)diffHtml+='<span class=\"diff-info diff-down\">IPA: '+d.diffMb.toFixed(2)+' MB</span> ';");
        html.append("else diffHtml+='<span class=\"diff-info diff-same\">IPA: no change</span> ';");
        html.append("}\n");
        html.append("if(data.diff&&data.diff.hap){var d=data.diff.hap;");
        html.append("if(d.diffMb>0)diffHtml+='<span class=\"diff-info diff-up\">HAP: +'+d.diffMb.toFixed(2)+' MB</span> ';");
        html.append("else if(d.diffMb<0)diffHtml+='<span class=\"diff-info diff-down\">HAP: '+d.diffMb.toFixed(2)+' MB</span> ';");
        html.append("else diffHtml+='<span class=\"diff-info diff-same\">HAP: no change</span> ';");
        html.append("}document.getElementById('diffArea').innerHTML=diffHtml;\n");

        // Build chart
        html.append("var legendData=[],series=[];\n");

        // APK series
        html.append("if(hasApk){legendData.push('APK Size');");
        html.append("series.push({name:'APK Size',type:'line',smooth:true,");
        html.append("symbol:'circle',symbolSize:6,");
        html.append("lineStyle:{width:2,color:'#0099ff'},");
        html.append("itemStyle:{color:'#0099ff'},");
        html.append("areaStyle:{color:new echarts.graphic.LinearGradient(0,0,0,1,[{offset:0,color:'rgba(0,153,255,0.3)'},{offset:1,color:'rgba(0,153,255,0.05)'}])},");
        html.append("data:data.apk.sizesMb.map(function(v){return v===null?null:Number(v);})");
        html.append("});}\n");

        // IPA series
        html.append("if(hasIpa){legendData.push('IPA Size');");
        html.append("series.push({name:'IPA Size',type:'line',smooth:true,");
        html.append("symbol:'diamond',symbolSize:6,");
        html.append("lineStyle:{width:2,color:'#ff6600'},");
        html.append("itemStyle:{color:'#ff6600'},");
        html.append("areaStyle:{color:new echarts.graphic.LinearGradient(0,0,0,1,[{offset:0,color:'rgba(255,102,0,0.3)'},{offset:1,color:'rgba(255,102,0,0.05)'}])},");
        html.append("data:data.ipa.sizesMb.map(function(v){return v===null?null:Number(v);})");
        html.append("});}\n");

        // HAP series
        html.append("if(hasHap){legendData.push('HAP Size');");
        html.append("series.push({name:'HAP Size',type:'line',smooth:true,");
        html.append("symbol:'triangle',symbolSize:6,");
        html.append("lineStyle:{width:2,color:'#00cc99'},");
        html.append("itemStyle:{color:'#00cc99'},");
        html.append("areaStyle:{color:new echarts.graphic.LinearGradient(0,0,0,1,[{offset:0,color:'rgba(0,204,153,0.3)'},{offset:1,color:'rgba(0,204,153,0.05)'}])},");
        html.append("data:data.hap.sizesMb.map(function(v){return v===null?null:Number(v);})");
        html.append("});}\n");

        html.append("document.getElementById('chart').style.display='block';\n");
        html.append("var chart=echarts.init(document.getElementById('chart'));\n");
        html.append("chart.setOption({\n");
        html.append("tooltip:{trigger:'axis',formatter:function(p){var idx=p[0].dataIndex;var r='Build: <b>#'+allBns[idx]+'</b><br/>';");
        html.append("p.forEach(function(pp){r+=pp.marker+' '+pp.seriesName+': <b>'+Number(pp.value).toFixed(2)+' MB</b><br/>';});return r;}},\n");
        html.append("legend:{data:legendData,bottom:10},\n");
        html.append("grid:{left:60,right:30,top:40,bottom:55},\n");
        html.append("xAxis:{name:'Build #',type:'category',data:allBns,axisLabel:{rotate:40,fontSize:11}},\n");
        html.append("yAxis:{name:'Size (MB)',type:'value'},\n");
        html.append("toolbox:{feature:{dataZoom:{yAxisIndex:'none'},restore:{},saveAsImage:{}}},\n");
        html.append("dataZoom:[{type:'inside',start:0,end:100},{start:0,end:100}],\n");
        html.append("series:series\n");
        html.append("});\n");
        html.append("window.addEventListener('resize',function(){chart.resize();});\n");

        // Table: last 5 builds
        html.append("var total=allBns.length;\n");
        html.append("var startIdx=Math.max(0,total-5);\n");
        html.append("var tblHtml='<table><tr>");
        html.append("<th>Build #</th><th>APK (MB)</th><th>IPA (MB)</th><th>HAP (MB)</th>");
        html.append("<th>Duration</th></tr>';\n");
        html.append("for(var i=startIdx;i<total;i++){\n");
        html.append("tblHtml+='<tr>';\n");
        html.append("tblHtml+='<td><b>#'+allBns[i]+'</b></td>';\n");
        html.append("tblHtml+='<td>'+(hasApk&&data.apk.sizesMb[i]?Number(data.apk.sizesMb[i]).toFixed(2):'-')+'</td>';\n");
        html.append("tblHtml+='<td>'+(hasIpa&&data.ipa.sizesMb[i]?Number(data.ipa.sizesMb[i]).toFixed(2):'-')+'</td>';\n");
        html.append("tblHtml+='<td>'+(hasHap&&data.hap.sizesMb[i]?Number(data.hap.sizesMb[i]).toFixed(2):'-')+'</td>';\n");
        html.append("var dur=(hasApk&&data.apk.durations&&data.apk.durations[i])||(hasIpa&&data.ipa.durations&&data.ipa.durations[i])||(hasHap&&data.hap.durations&&data.hap.durations[i])||'-';\n");
        html.append("tblHtml+='<td>'+dur+'</td>';\n");
        html.append("tblHtml+='</tr>';\n");
        html.append("}\n");
        html.append("tblHtml+='</table>';\n");
        html.append("document.getElementById('dataTable').innerHTML=tblHtml;\n");

        html.append("}\n"); // end renderChart()

        html.append("if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',initChart)}else{initChart()}\n");
        html.append("</script>");

        html.append("</body></html>");
        rsp.getWriter().print(html.toString());
    }

    // ---- Compact chart widget for embedding in build page summary ----

    public void doWidget(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        rsp.setContentType("text/html;charset=UTF-8");

        String json = buildTrendJson();

        // Inline echarts from classpath
        String echartsJs = "";
        try (var is = getClass().getResourceAsStream("/js/echarts.min.js")) {
            if (is != null) {
                echartsJs = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to inline echarts: " + e.getMessage());
        }

        var html = new StringBuilder(4096);
        html.append("<!DOCTYPE html><html><head>");
        html.append("<meta charset=\"UTF-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        html.append("<style>");
        html.append("*{margin:0;padding:0;box-sizing:border-box}");
        html.append("body{background:transparent;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;overflow:hidden}");
        html.append("#chart{width:100%;height:240px}");
        html.append(".no-data{text-align:center;padding:60px 20px;color:#999;font-size:14px}");
        html.append(".loading{text-align:center;padding:60px 20px;color:#999;font-size:13px}");
        html.append(".diff-bar{font-size:16px;font-weight:600;padding:6px 10px 2px;color:#333;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}");
        html.append(".diff-bar .up{color:#cc3333}");
        html.append(".diff-bar .down{color:#339933}");
        html.append(".diff-bar .sep{color:#ddd;margin:0 6px}");
        html.append("</style>");
        if (!echartsJs.isEmpty()) {
            html.append("<script>").append(echartsJs).append("</script>");
        }
        html.append("</head><body>");

        html.append("<div id=\"diffBar\" class=\"diff-bar\"></div>");
        html.append("<div id=\"noData\" class=\"no-data\" style=\"display:none\">No data</div>");
        html.append("<div id=\"loadingMsg\" class=\"loading\">Loading...</div>");
        html.append("<div id=\"chart\" style=\"display:none\"></div>");

        html.append("<script>");
        html.append("var chartData = ").append(json).append(";\n");
        html.append("function init(){\n");
        html.append("document.getElementById('loadingMsg').style.display='none';\n");
        html.append("var d=chartData;\n");
        html.append("var hasApk=d.apk&&d.apk.hasData;\n");
        html.append("var hasIpa=d.ipa&&d.ipa.hasData;\n");
        html.append("var hasHap=d.hap&&d.hap.hasData;\n");
        html.append("if(!hasApk&&!hasIpa&&!hasHap){document.getElementById('noData').style.display='block';return;}\n");
        html.append("document.getElementById('chart').style.display='block';\n");
        html.append("var allBns=d.allBuildNumbers||[];\n");
        html.append("var legend=[],series=[];\n");

        // Diff banner
        html.append("var db=document.getElementById('diffBar');var dh='';\n");
        html.append("if(d.diff&&d.diff.apk){var a=d.diff.apk;dh+='APK #'+a.latestBN+' <span class=\"'+ (a.diffMb>0?'up':'down') +'\">'+ (a.diffMb>0?'+':'') +a.diffMb.toFixed(2)+'MB</span> vs #'+a.prevBN;}\n");
        html.append("if(d.diff&&d.diff.ipa){if(dh)dh+='<span class=\"sep\">|</span>';var a=d.diff.ipa;dh+='IPA #'+a.latestBN+' <span class=\"'+ (a.diffMb>0?'up':'down') +'\">'+ (a.diffMb>0?'+':'') +a.diffMb.toFixed(2)+'MB</span> vs #'+a.prevBN;}\n");
        html.append("if(d.diff&&d.diff.hap){if(dh)dh+='<span class=\"sep\">|</span>';var a=d.diff.hap;dh+='HAP #'+a.latestBN+' <span class=\"'+ (a.diffMb>0?'up':'down') +'\">'+ (a.diffMb>0?'+':'') +a.diffMb.toFixed(2)+'MB</span> vs #'+a.prevBN;}\n");
        html.append("db.innerHTML=dh;\n");

        // APK
        html.append("if(hasApk){legend.push('APK');");
        html.append("series.push({name:'APK',type:'line',smooth:true,");
        html.append("symbol:'circle',symbolSize:4,lineStyle:{width:1.5,color:'#0099ff'},");
        html.append("itemStyle:{color:'#0099ff'},");
        html.append("areaStyle:{color:new echarts.graphic.LinearGradient(0,0,0,1,[{offset:0,color:'rgba(0,153,255,0.25)'},{offset:1,color:'rgba(0,153,255,0.02)'}])},");
        html.append("data:d.apk.sizesMb.map(function(v){return v===null?null:Number(v);})});}\n");
        // IPA
        html.append("if(hasIpa){legend.push('IPA');");
        html.append("series.push({name:'IPA',type:'line',smooth:true,");
        html.append("symbol:'diamond',symbolSize:4,lineStyle:{width:1.5,color:'#ff6600'},");
        html.append("itemStyle:{color:'#ff6600'},");
        html.append("areaStyle:{color:new echarts.graphic.LinearGradient(0,0,0,1,[{offset:0,color:'rgba(255,102,0,0.25)'},{offset:1,color:'rgba(255,102,0,0.02)'}])},");
        html.append("data:d.ipa.sizesMb.map(function(v){return v===null?null:Number(v);})});}\n");
        // HAP
        html.append("if(hasHap){legend.push('HAP');");
        html.append("series.push({name:'HAP',type:'line',smooth:true,");
        html.append("symbol:'triangle',symbolSize:4,lineStyle:{width:1.5,color:'#00cc99'},");
        html.append("itemStyle:{color:'#00cc99'},");
        html.append("areaStyle:{color:new echarts.graphic.LinearGradient(0,0,0,1,[{offset:0,color:'rgba(0,204,153,0.25)'},{offset:1,color:'rgba(0,204,153,0.02)'}])},");
        html.append("data:d.hap.sizesMb.map(function(v){return v===null?null:Number(v);})});}\n");
        // Chart
        html.append("var chart=echarts.init(document.getElementById('chart'));\n");
        html.append("chart.setOption({\n");
        html.append("tooltip:{trigger:'axis',formatter:function(p){var r='Build <b>#'+allBns[p[0].dataIndex]+'</b><br/>';");
        html.append("p.forEach(function(pp){r+=pp.marker+' '+pp.seriesName+': <b>'+Number(pp.value).toFixed(2)+' MB</b><br/>';});return r;}},\n");
        html.append("legend:{data:legend,show:true,bottom:2,icon:'circle',itemWidth:8,itemHeight:8,textStyle:{fontSize:11}},\n");
        html.append("grid:{left:35,right:10,top:20,bottom:30},\n");
        html.append("xAxis:{type:'category',data:allBns,axisLabel:{fontSize:10,rotate:30}},\n");
        html.append("yAxis:{name:'MB',nameTextStyle:{fontSize:10},type:'value',axisLabel:{fontSize:10}},\n");
        html.append("series:series\n");
        html.append("});\n");
        html.append("window.addEventListener('resize',function(){chart.resize();});\n");
        html.append("}\n");
        html.append("if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',init)}else{init()}\n");
        html.append("</script>");

        html.append("</body></html>");
        rsp.getWriter().print(html.toString());
    }

    // ---- JSON data API ----

    public void doData(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        long start = System.currentTimeMillis();
        String json = buildTrendJson();
        long elapsed = System.currentTimeMillis() - start;

        LOGGER.info("doData() for " + (job != null ? job.getFullName() : "null")
            + " — generated " + json.length() + " chars in " + elapsed + "ms");

        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getWriter().print(json);
    }

    public Job<?, ?> getJob() {
        return job;
    }

    // ---- Track flags: read from job's publisher config ----

    /** Read which platforms the job is configured to track. Defaults to all true. */
    private boolean[] getTrackFlags() {
        boolean trackApk = true, trackIpa = true, trackHap = true;
        if (job instanceof AbstractProject) {
            AbstractProject<?, ?> p = (AbstractProject<?, ?>) job;
            for (Publisher pub : p.getPublishersList()) {
                if (pub instanceof ApkSizePublisher) {
                    ApkSizePublisher asp = (ApkSizePublisher) pub;
                    trackApk = asp.isTrackAndroid();
                    trackIpa = asp.isTrackIos();
                    trackHap = asp.isTrackHarmony();
                    break;
                }
            }
        }
        return new boolean[]{trackApk, trackIpa, trackHap};
    }

    // ---- Data loading: file-backed, with automatic backfill ----

    private String buildTrendJson() {
        if (job == null) {
            LOGGER.warning("buildTrendJson() called but job is null");
            return "{}";
        }

        boolean[] flags = getTrackFlags();

        // Fast path: read from persistent data file
        List<ApkSizeDataStore.BuildRecord> records = ApkSizeDataStore.loadBuilds(job);

        if (records != null && !needsBackfill(records)) {
            LOGGER.fine("Loaded " + records.size() + " records from data file (comprehensive)");
            return ApkSizeDataStore.toChartJson(job, records, flags[0], flags[1], flags[2]);
        }

        if (records != null) {
            // File exists but sparse — Publisher created it before we scanned history
            LOGGER.info("Data file has only " + records.size() + " records but job has "
                + job.getBuilds().size() + " builds — backfilling...");
        } else {
            // File doesn't exist at all
            LOGGER.info("Data file not found for " + job.getFullName() + " — performing initial scan...");
        }

        // Full scan
        List<ApkSizeDataStore.BuildRecord> scanned = ApkSizeDataStore.scanAllBuilds(job);

        // Merge: prefer scanned (has history), fallback to file records
        List<ApkSizeDataStore.BuildRecord> merged;
        if (!scanned.isEmpty() && (records == null || scanned.size() >= records.size())) {
            merged = scanned;
        } else if (records != null) {
            merged = records;
        } else {
            merged = scanned;
        }

        // Persist
        if (!merged.isEmpty()) {
            ApkSizeDataStore.saveBuilds(job, merged);
            LOGGER.info("Data file saved (" + merged.size() + " records)");
        }

        return ApkSizeDataStore.toChartJson(job, merged, flags[0], flags[1], flags[2]);
    }

    /**
     * Check if the data file is too sparse vs actual builds.
     * Triggers a backfill scan when the file was created by Publisher
     * before the initial historical scan ran.
     */
    private boolean needsBackfill(List<ApkSizeDataStore.BuildRecord> records) {
        if (records == null || records.isEmpty()) return true;
        int totalBuilds = job.getBuilds().size();
        if (totalBuilds <= 1) return false;             // legitimately only 1 build
        // Only skip backfill if we have most of the builds or total is small.
        // This handles upgrades where SCAN_LIMIT/MAX_BUILDS was increased.
        if (totalBuilds <= 100 || records.size() >= totalBuilds * 0.9) return false;
        return records.size() < totalBuilds;            // sparse vs actual
    }

    // ---- Helpers ----

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
