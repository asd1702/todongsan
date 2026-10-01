import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import jdk.jfr.consumer.*;

/** Correlates k6, MySQL timers and JFR without relying on application log gaps alone. */
public class StallAnalyze {
    static String field(String line, String key) {
        Matcher m = Pattern.compile("\\\"" + key + "\\\":(?:\\\"([^\\\"]*)\\\"|([-0-9.eE+]+))").matcher(line);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : "";
    }
    static double num(String s) { return s.isEmpty() ? 0 : Double.parseDouble(s); }
    static String shortText(String s) { return s.length() > 150 ? s.substring(0,150) : s; }
    public static void main(String[] args) throws Exception {
        Path capture=Path.of(args[0]), run=Path.of(args[1]);
        double t0=0, api=0, mpDone=Double.parseDouble(Files.readString(run.resolve("mp_done")).trim());
        List<double[]> spends=new ArrayList<>();
        for(String line:Files.readAllLines(run.resolve("k6.json"))) {
            if(!line.contains("\"type\":\"Point\"")) continue;
            String metric=field(line,"metric");
            if(metric.equals("settle_start_ms")) t0=num(field(line,"value"));
            if(metric.equals("settle_api_ms")) api=num(field(line,"value"));
            if(metric.equals("http_req_duration") && field(line,"name").equals("spend")) {
                double finish=OffsetDateTime.parse(field(line,"time")).toInstant().toEpochMilli();
                double duration=num(field(line,"value"));
                spends.add(new double[]{finish-duration,finish,duration});
            }
        }
        StringBuilder out=new StringBuilder();
        out.append("t0_vm_ms=").append((long)t0).append("\napi_ms=").append(api).append("\nmp_done_ms=").append(mpDone-t0).append('\n');
        List<String> clock=Files.readAllLines(capture.resolve("clock.tsv"));
        String[] anchor=clock.get(0).split("\t");
        double anchorEpoch=num(anchor[2])/1000, anchorTimer=num(anchor[3]);
        List<Double> offsets=new ArrayList<>();
        double longestSample=0;
        for(String line:clock) {
            String[] a=line.split("\t");
            offsets.add(num(a[2])/1000-(num(a[0])+num(a[1]))/2);
            longestSample=Math.max(longestSample,num(a[1])-num(a[0]));
        }
        Collections.sort(offsets);
        double offset=offsets.get(offsets.size()/2);
        // Waits use CYCLE timers, statements use NANOSECOND timers. Calibrate them separately.
        List<double[]> waitClock=new ArrayList<>();
        for(String line:Files.readAllLines(capture.resolve("current.tsv"))) {
            String[] a=line.split("\t",-1);
            double epoch=(num(a[0])+num(a[1]))/2+offset;
            if(a.length>16 && a[16].isEmpty() && !a[9].equals("idle") && num(a[11])>=2000000000L
                    && epoch>=t0-500 && epoch<=mpDone+500)
                waitClock.add(new double[]{num(a[15])/1e9,epoch});
        }
        double wx=waitClock.stream().mapToDouble(a->a[0]).average().orElse(Double.NaN);
        double wy=waitClock.stream().mapToDouble(a->a[1]).average().orElse(Double.NaN);
        double covariance=0,variance=0;
        for(double[] a:waitClock){covariance+=(a[0]-wx)*(a[1]-wy);variance+=(a[0]-wx)*(a[0]-wx);}
        double waitScale=covariance/variance;
        double waitFitError=0;
        for(double[] a:waitClock)waitFitError=Math.max(waitFitError,Math.abs(wy+waitScale*(a[0]-wx)-a[1]));
        out.append("wait_timer_scale=").append(waitScale).append("\nwait_clock_max_fit_error_ms=").append(waitFitError).append('\n');
        out.append("vm_minus_host_ms_median=").append(offset).append("\nclock_query_max_ms=").append(longestSample).append('\n');
        List<Double> finishes=new ArrayList<>();
        for(double[] s:spends) if(s[1]>=t0+api && s[1]<=mpDone+1000) finishes.add(s[1]);
        Collections.sort(finishes);
        double maxGap=0,gapEnd=0;
        for(int i=1;i<finishes.size();i++) if(finishes.get(i)-finishes.get(i-1)>maxGap) {
            maxGap=finishes.get(i)-finishes.get(i-1);gapEnd=finishes.get(i);
        }
        out.append("spend_completion_max_gap_ms=").append(maxGap).append("\ngap_end_t0_ms=").append(gapEnd-t0).append('\n');
        long slow=spends.stream().filter(s->s[2]>=200).count();
        out.append("spend_over_200ms_whole_run=").append(slow).append('\n');
        Path bins=capture.resolve("timeline-100ms.tsv");
        StringBuilder timeline=new StringBuilder("t0_ms\tstarts\tfinishes\tmax_latency_ms\n");
        for(double begin=t0;begin<=mpDone+1000;begin+=100) {
            int starts=0,ends=0;double max=0;
            for(double[] s:spends){if(s[0]>=begin&&s[0]<begin+100)starts++;if(s[1]>=begin&&s[1]<begin+100){ends++;max=Math.max(max,s[2]);}}
            timeline.append((long)(begin-t0)).append('\t').append(starts).append('\t').append(ends).append('\t').append(max).append('\n');
        }
        Files.writeString(bins,timeline);
        StringBuilder invalidTimers=new StringBuilder();
        for(String kind:List.of("waits","statements")) {
            List<String[]> rows=new ArrayList<>();
            for(String line:Files.readAllLines(capture.resolve(kind+".tsv"))) {
                String[] a=line.split("\t",-1);
                if(a[4].equals("idle"))continue;
                if(num(a[6])<num(a[5])) { invalidTimers.append(kind).append('\t').append(line).append('\n');continue; }
                double start=kind.equals("waits")?wy+waitScale*(num(a[5])/1e9-wx):anchorEpoch+(num(a[5])-anchorTimer)/1e9;
                double end=kind.equals("waits")?wy+waitScale*(num(a[6])/1e9-wx):anchorEpoch+(num(a[6])-anchorTimer)/1e9;
                if(kind.equals("waits")&&a[4].contains("MYSQL_BIN_LOG::update_cond"))continue;
                if(end>=t0-1000&&start<=mpDone+1000) rows.add(a);
            }
            rows.sort((a,b)->Double.compare(num(b[7]),num(a[7])));
            out.append("\nTOP ").append(kind).append(" (start/end relative to t0, ms):\n");
            for(String[] a:rows.subList(0,Math.min(18,rows.size()))) {
                double start=(kind.equals("waits")?wy+waitScale*(num(a[5])/1e9-wx):anchorEpoch+(num(a[5])-anchorTimer)/1e9)-t0;
                double end=(kind.equals("waits")?wy+waitScale*(num(a[6])/1e9-wx):anchorEpoch+(num(a[6])-anchorTimer)/1e9)-t0;
                out.append(String.format(Locale.ROOT,"%.1f..%.1f duration=%.1f thread=%s %s %s %s%n",start,end,num(a[7])/1e9,a[2],a[4],a[8],shortText(a[9])));
                if(kind.equals("statements")&&a.length>10)out.append("  ").append(shortText(a[10])).append('\n');
            }
        }
        Files.writeString(capture.resolve("invalid-timers.tsv"),invalidTimers);
        out.append("\nJFR slow application-thread events (VM time adjusted):\n");
        List<RecordedEvent> events=new ArrayList<>();
        try(RecordingFile recording=new RecordingFile(capture.resolve("mp.jfr"))) {
            while(recording.hasMoreEvents()) {
                RecordedEvent e=recording.readEvent();
                double start=e.getStartTime().toEpochMilli()+offset, end=e.getEndTime().toEpochMilli()+offset;
                String type=e.getEventType().getName();
                if(end<t0-1000||start>mpDone+1000)continue;
                double duration=e.getDuration().toNanos()/1e6;
                String thread=e.getThread()==null?"":e.getThread().getJavaName();
                if((type.equals("jdk.SocketRead")||type.equals("jdk.SocketWrite")||type.equals("jdk.ThreadPark")||type.equals("jdk.JavaMonitorEnter")||type.equals("jdk.FileWrite"))
                        &&duration>=100&&(thread.contains("http-nio")||thread.contains("KafkaListener"))) {
                    String stack=e.getStackTrace()==null?"":e.getStackTrace().toString();
                    boolean idle=stack.contains("TaskQueue.poll") || (type.equals("jdk.ThreadPark") && !stack.contains("hikari"));
                    boolean unrelatedSocket=e.hasField("port")&&e.getInt("port")!=3307;
                    if(!idle&&!unrelatedSocket)events.add(e);
                }
                if(type.equals("jdk.GCPhasePause")) out.append(String.format(Locale.ROOT,"GC %.1fms at t0+%.1fms%n",duration,start-t0));
            }
        }
        events.sort((a,b)->b.getDuration().compareTo(a.getDuration()));
        out.append("slow_event_count=").append(events.size()).append('\n');
        for(RecordedEvent e:events.subList(0,Math.min(30,events.size()))) {
            out.append(String.format(Locale.ROOT,"%s %.1fms at t0+%.1f thread=%s%n",e.getEventType().getName(),e.getDuration().toNanos()/1e6,e.getStartTime().toEpochMilli()+offset-t0,e.getThread().getJavaName()));
            if(e.hasField("host"))out.append("  host=").append(e.getString("host")).append(" port=").append(e.getInt("port")).append('\n');
            if(e.getStackTrace()!=null)for(var frame:e.getStackTrace().getFrames().subList(0,Math.min(9,e.getStackTrace().getFrames().size())))
                out.append("  ").append(frame.getMethod().getType().getName()).append('.').append(frame.getMethod().getName()).append('\n');
        }
        Files.writeString(capture.resolve("correlation.txt"),out);
        System.out.print(out);
    }
}
