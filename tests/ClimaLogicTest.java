import com.historicoclima.CityRowAdapter;
import com.historicoclima.CitySearch;
import com.historicoclima.TabController;
import java.util.List;

/** Testes consistentes Clima (sem Android): javac + java apenas. */
public class ClimaLogicTest {
    static int pass = 0, fail = 0;
    static void check(boolean c, String n) {
        if (c) { pass++; System.out.println("PASS " + n); }
        else { fail++; System.out.println("FAIL " + n); }
    }

    public static void main(String[] a) {
        // normStr
        check(CitySearch.normStr("São Paulo").equals("sao paulo"), "norm sao");
        check(CitySearch.normStr("Açailândia").equals("acailandia"), "norm cedilha");
        check(CitySearch.normStr(null).equals(""), "norm null");
        check(CitySearch.normStr("Curitiba").equals("curitiba"), "norm simples");

        // filter: base pequena determinística
        String[] names = {"sao paulo sp", "sao luis ma", "curitiba pr", "santos sp", "sao paulo sp zona"};
        List<Integer> r1 = CitySearch.filter("sao", names, 60);
        check(r1.size() == 3, "filter sao=3 (startswith)");
        check(r1.get(0) == 0, "startswith primeiro");

        List<Integer> r2 = CitySearch.filter("s", names, 60);
        check(r2.isEmpty(), "query curta vazia");

        List<Integer> r3 = CitySearch.filter("zzz", names, 60);
        check(r3.isEmpty(), "sem match vazio");

        // limite max respeitado, sem O(n²) (contrato)
        String[] big = new String[500];
        for (int i = 0; i < big.length; i++) big[i] = "sao cidade " + i;
        List<Integer> r4 = CitySearch.filter("sao", big, 15);
        check(r4.size() == 15, "limite 15");

        // "sao paulo" com espaço usa contains
        List<Integer> r5 = CitySearch.filter("sao paulo", names, 60);
        check(r5.size() == 2 && r5.contains(0) && r5.contains(4), "multi-termo");

        // TabController
        TabController tc = new TabController(4);
        check(tc.getCurrent() == -1, "tab inicial -1");
        check(tc.switchTo(0), "troca 0");
        check(!tc.switchTo(0), "mesma aba sem troca");
        check(!tc.switchTo(99), "indice invalido");
        check(tc.isBuilt(0) && !tc.isBuilt(1), "built flag");
        check(tc.switchTo(2), "troca 2");
        check(tc.getActivations(0) == 1 && tc.getActivations(2) == 1, "ativacoes");
        tc.switchTo(0);
        check(tc.getActivations(0) == 2, "reativacao conta");
        check(tc.getCurrent() == 0, "current 0");
        tc.invalidate(0);
        check(!tc.isBuilt(0), "invalidate limpa built");
        check(tc.getCurrent() == 0, "invalidate mantem current");

        // CityRowAdapter: pagina 15 + ver mais até 60
        CityRowAdapter ad = new CityRowAdapter();
        String[] bigN = new String[100];
        for (int i = 0; i < bigN.length; i++) bigN[i] = "sao cidade " + i;
        ad.setNormNames(bigN);
        ad.query("sao");
        check(ad.total() == 60, "adapter total cap 60");
        check(ad.visible().size() == 15, "adapter pagina 15");
        check(ad.remaining() == 45, "adapter remaining 45");
        check(ad.showMore(), "showMore cresce");
        check(ad.visible().size() == 60 && ad.remaining() == 0, "showMore total");
        check(!ad.showMore(), "showMore idempotente");
        ad.query("zzz");
        check(ad.total() == 0 && ad.visible().isEmpty(), "adapter vazio");
        ad.query("s");
        check(ad.total() == 0, "adapter query curta");

        System.out.println("ClimaLogicTest: " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
