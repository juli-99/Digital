/*
 * Copyright (c) 2018 Ivan Deras.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.hdl.verilog2;

import de.neemann.digital.core.NodeException;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.PinException;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.hdl.hgs.HGSEvalException;
import de.neemann.digital.hdl.model2.HDLCircuit;
import de.neemann.digital.hdl.model2.HDLException;
import de.neemann.digital.hdl.model2.HDLModel;
import de.neemann.digital.hdl.model2.HDLNet;
import de.neemann.digital.hdl.model2.clock.HDLClockIntegrator;
import de.neemann.digital.hdl.printer.CodePrinter;
import de.neemann.digital.hdl.boards.BoardInterface;
import de.neemann.digital.hdl.boards.BoardProvider;
import de.neemann.digital.lang.Lang;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Used to create the verilog output
 */
public class VerilogGenerator implements Closeable {

    private final ElementLibrary library;
    private final CodePrinter out;
    private ArrayList<File> testBenches;
    private boolean useClockIntegration = true;

    /**
     * Creates a new exporter
     *
     * @param library the library
     * @param out     the output stream
     */
    public VerilogGenerator(ElementLibrary library, CodePrinter out) {
        this.library = library;
        this.out = out;
    }

    /**
     * Exports the given circuit
     *
     * @param circuit the circuit to export
     * @return this for chained calls
     * @throws IOException IOException
     */
    public VerilogGenerator export(Circuit circuit) throws IOException {
        try {

            if (!circuit.getAttributes().get(Keys.ROMMANAGER).isEmpty())
                throw new HDLException(Lang.get("err_centralDefinedRomsAreNotSupported"));

            BoardInterface board = BoardProvider.getInstance().getBoard(circuit);

            HDLClockIntegrator clockIntegrator = null;
            if (board != null && useClockIntegration)
                clockIntegrator = board.getClockIntegrator();

            HDLModel model = new HDLModel(library).create(circuit, clockIntegrator);
            for (HDLCircuit hdlCircuit : model)
                hdlCircuit.applyDefaultOptimizations();

            HDLModel.Renaming vrename = new VerilogRenaming();
            model.renameLabels(vrename);

            for (HDLCircuit hdlCircuit : model)
                checkForUniqueNetNames(hdlCircuit);

            out.println("/*");
            out.println(" * Generated by Digital. Don't modify this file!");
            out.println(" * Any changes will be lost if this file is regenerated.");
            out.println(" */");

            String fileName = out.getFile() != null? out.getFile().getName() : circuit.getOrigin().getName();
            String[] tokens = fileName.split("(?=(\\.[^\\.]+)$)"); // The power of regex :)

            String topModuleName = vrename.checkName(tokens[0]);

            new VerilogCreator(out).printHDLCircuit(model.getMain(), topModuleName);

            File outFile = out.getFile();
            if (outFile != null) {
                testBenches = new VerilogTestBenchCreator(circuit, model, topModuleName)
                        .write(outFile)
                        .getTestFileWritten();

                if (board != null)
                    board.writeFiles(outFile, model);
            }

            return this;
        } catch (PinException | NodeException | HDLException | HGSEvalException e) {
            throw new IOException(Lang.get("err_vhdlExporting"), e);
        }
    }

    private void checkForUniqueNetNames(HDLCircuit hdlCircuit) throws HDLException {
        ArrayList<HDLNet> nets = hdlCircuit.getNets();
        // try to resolve duplicate names
        for (HDLNet n : nets)
            if (n.isUserNamed())
                for (HDLNet nn : nets)
                    if (n.getName().equalsIgnoreCase(nn.getName()) && n != nn) {
                        String newName = "s_" + n.getName();
                        int i = 1;
                        while (exits(newName, nets))
                            newName = "s_" + n.getName() + (i++);
                        n.setName(newName);
                    }

        // throw an exception if there is still a duplicate name
        for (int i = 0; i < nets.size(); i++) {
            final HDLNet n1 = nets.get(i);
            for (int j = i + 1; j < nets.size(); j++) {
                final HDLNet n2 = nets.get(j);
                if (n1.getName().equalsIgnoreCase(n2.getName()))
                    throw new HDLException(
                            Lang.get("err_namesAreNotUnique_N", n1.getName() + "==" + n2.getName()),
                            hdlCircuit.getOrigin());
            }
        }
    }

    private boolean exits(String newName, ArrayList<HDLNet> nets) {
        for (HDLNet n : nets)
            if (n.getName().equalsIgnoreCase(newName))
                return true;
        return false;
    }

    /**
     * @return the test bench files, maybe null
     */
    public ArrayList<File> getTestBenches() {
        return testBenches;
    }

    @Override
    public String toString() {
        return out.toString();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    /**
     * Disables the clock integration.
     * Used only for the tests.
     *
     * @return this for chained calls
     */
    public VerilogGenerator disableClockIntegration() {
        useClockIntegration = false;
        return this;
    }
}
