package cn.siyue.core;

/** Canonicalizes exactly the legacy academic dictionary aliases; unknown historic labels are retained. */
final class AcademicGradeNormalizer {
    private static final String NUMBERS="一二三四五六七八九";
    private static final java.util.regex.Pattern GRADE = java.util.regex.Pattern
        .compile("^(小学|小|初中|初|高中|高)?([一二三四五六七八九1-9])(?:年级|年)?$");
    private AcademicGradeNormalizer() { }
    static String normalize(String value) {
        String raw=value==null?"":value.trim();
        if(raw.isEmpty()) return raw;
        String key=java.text.Normalizer.normalize(raw,java.text.Normalizer.Form.NFKC).replaceAll("\\s+","");
        var match=GRADE.matcher(key);
        if(!match.matches()) return raw;
        return canonicalGrade(raw,key,match);
    }
    private static String canonicalGrade(String raw,String key,java.util.regex.Matcher match) {
        int number=gradeNumber(match.group(2).charAt(0));
        String stage=stage(match.group(1));
        if(stage==null) {
            if(!key.endsWith("年级")&&!key.endsWith("年")) return raw;
            stage=number>6?"初中":"小学";
            if(number>6) number-=6;
        }
        if(number<1||number>maximum(stage)) return raw;
        return stage+NUMBERS.charAt(number-1)+"年级";
    }
    private static int gradeNumber(char digit) {
        return digit>='1'&&digit<='9'?digit-'0':NUMBERS.indexOf(digit)+1;
    }
    private static String stage(String value) {
        if(value==null) return null;
        return value.startsWith("小")?"小学":value.startsWith("初")?"初中":"高中";
    }
    private static int maximum(String stage) {
        return stage.equals("小学")?6:3;
    }
}
