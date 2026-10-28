package com.yunjihai.rag.qa.conflict;

import com.yunjihai.rag.retrieval.domain.Evidence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class DeterministicEvidenceConflictDetector implements EvidenceConflictDetector {
    private static final Pattern FACT=Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9_（）()·\\-/]{2,40})\\s*"
                    + "(?:设置为|设为|定为|[:：]|为|是)\\s*([^\\n。；;，,]{1,40})");
    private static final Pattern DATE=Pattern.compile(
            "^(\\d{4})\\s*(?:年|[-/.])\\s*(\\d{1,2})\\s*(?:月|[-/.])\\s*(\\d{1,2})\\s*日?");
    private static final Pattern QUANTITY=Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*(万元|元|个月|月|年|天|日|GB|MB|TPS|QPS|%|名|人|台)",
            Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static final Pattern STATE=Pattern.compile(
            "^(启用|开启|生效|有效|允许|停用|关闭|失效|无效|禁止|是|否)$");

    @Override
    public boolean hasConflict(List<Evidence> evidence){
        var items=List.copyOf(evidence);
        if(hasIdentityConflict(items))return true;
        var facts=new ArrayList<Fact>();
        items.forEach(item->facts.addAll(extract(item)));
        for(int left=0;left<facts.size();left++){
            for(int right=left+1;right<facts.size();right++){
                Fact first=facts.get(left);
                Fact second=facts.get(right);
                if(!first.evidenceId().equals(second.evidenceId())
                        && keysMatch(first.key(),second.key())
                        && !first.value().equals(second.value()))return true;
            }
        }
        return false;
    }

    private static boolean hasIdentityConflict(List<Evidence> evidence){
        var seen=new HashMap<EvidenceIdentity,String>();
        for(Evidence item:evidence){
            var identity=new EvidenceIdentity(
                    item.spaceId(),item.documentId(),item.versionId(),item.nodeId());
            String content=normalizeContent(item.title())+'\n'+normalizeContent(item.excerpt());
            String existing=seen.putIfAbsent(identity,content);
            if(existing!=null&&!existing.equals(content))return true;
        }
        return false;
    }

    private static List<Fact> extract(Evidence evidence){
        var facts=new ArrayList<Fact>();
        var matcher=FACT.matcher(Normalizer.normalize(evidence.excerpt(),Normalizer.Form.NFKC));
        while(matcher.find()){
            String key=normalizeKey(matcher.group(1));
            canonicalValue(matcher.group(2)).ifPresent(value->
                    facts.add(new Fact(evidence.evidenceId(),key,value)));
        }
        return List.copyOf(facts);
    }

    private static Optional<String> canonicalValue(String raw){
        String value=Normalizer.normalize(raw,Normalizer.Form.NFKC).trim();
        var date=DATE.matcher(value);
        if(date.find()){
            try{
                return Optional.of("DATE:"+LocalDate.of(
                        Integer.parseInt(date.group(1)),
                        Integer.parseInt(date.group(2)),
                        Integer.parseInt(date.group(3))));
            }catch(DateTimeException invalid){return Optional.empty();}
        }
        var quantity=QUANTITY.matcher(value);
        if(quantity.find())return Optional.of(canonicalQuantity(
                new BigDecimal(quantity.group(1)),quantity.group(2).toUpperCase(Locale.ROOT)));
        var state=STATE.matcher(value);
        if(state.find())return Optional.of(switch(state.group(1)){
            case "启用","开启","生效","有效","允许","是"->"STATE:ENABLED";
            default->"STATE:DISABLED";
        });
        return Optional.empty();
    }

    private static String canonicalQuantity(BigDecimal number,String unit){
        return switch(unit){
            case "万元"->canonical("AMOUNT_YUAN",number.multiply(BigDecimal.valueOf(10_000)));
            case "元"->canonical("AMOUNT_YUAN",number);
            case "年"->canonical("DURATION_MONTH",number.multiply(BigDecimal.valueOf(12)));
            case "个月","月"->canonical("DURATION_MONTH",number);
            case "天","日"->canonical("DURATION_DAY",number);
            case "GB"->canonical("CAPACITY_MB",number.multiply(BigDecimal.valueOf(1_024)));
            case "MB"->canonical("CAPACITY_MB",number);
            case "%"->canonical("PERCENT",number);
            case "TPS"->canonical("TPS",number);
            case "QPS"->canonical("QPS",number);
            case "名","人"->canonical("PERSON_COUNT",number);
            case "台"->canonical("DEVICE_COUNT",number);
            default->throw new IllegalArgumentException("unsupported quantity unit");
        };
    }

    private static String canonical(String kind,BigDecimal value){
        return kind+':'+value.setScale(12,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static boolean keysMatch(String left,String right){
        if(left.equals(right))return true;
        String shorter=left.codePointCount(0,left.length())<right.codePointCount(0,right.length())
                ?left:right;
        String longer=shorter==left?right:left;
        return shorter.codePointCount(0,shorter.length())>=4&&longer.endsWith(shorter);
    }

    private static String normalizeKey(String value){
        return Normalizer.normalize(value,Normalizer.Form.NFKC)
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]","")
                .toUpperCase(Locale.ROOT);
    }

    private static String normalizeContent(String value){
        return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("\\s+","").trim();
    }

    private record Fact(UUID evidenceId,String key,String value){}
    private record EvidenceIdentity(UUID spaceId,UUID documentId,UUID versionId,String nodeId){}
}
