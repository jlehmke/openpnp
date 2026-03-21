import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.importer.KicadModImporter;
import org.openpnp.model.Footprint.Pad;

import static org.junit.jupiter.api.Assertions.*;

public class KicadModImporterTest {

    @Test
    public void testRectPads() throws Exception {
        File file = new File("samples/test", "R_0201_0603Metric.kicad_mod");
        List<Pad> pads = new KicadModImporter(file).getPads();
        assertEquals(2, pads.size());

        Pad p1 = pads.get(0);
        assertEquals("1", p1.getName());
        assertEquals(0.6, p1.getWidth(), 0.001);
        assertEquals(0.76, p1.getHeight(), 0.001);
        assertEquals(-0.51, p1.getX(), 0.001);
        assertEquals(0.0, p1.getY(), 0.001);
        assertEquals(0.0, p1.getRoundness(), 0.001);

        Pad p2 = pads.get(1);
        assertEquals("2", p2.getName());
        assertEquals(0.51, p2.getX(), 0.001);
    }

    @Test
    public void testMultilinePads() throws Exception {
        // Verifies the multiline pad definition fix (PR #1758 / KiCad 8 format)
        File file = new File("samples/test", "multiline_pad.kicad_mod");
        List<Pad> pads = new KicadModImporter(file).getPads();
        assertEquals(2, pads.size());

        Pad p1 = pads.get(0);
        assertEquals("1", p1.getName());
        assertEquals(0.6, p1.getWidth(), 0.001);
        assertEquals(1.55, p1.getHeight(), 0.001);
        assertEquals(25.0, p1.getRoundness(), 0.001); // roundrect_rratio 0.25 * 100
    }
}
