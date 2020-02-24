/*
 * Copyright (c) 2011-2020, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.app;

import boofcv.alg.fiducial.dots.UchiyaMarkerGenerator;
import boofcv.app.fiducials.CreateFiducialDocumentImage;
import boofcv.app.fiducials.CreateFiducialDocumentPDF;
import boofcv.app.uchiya.CreateUchiyaDocumentImage;
import boofcv.app.uchiya.CreateUchiyaDocumentPDF;
import boofcv.generate.Unit;
import boofcv.gui.BoofSwingUtil;
import boofcv.io.fiducial.FiducialIO;
import boofcv.io.fiducial.UchiyaDefinition;
import georegression.struct.point.Point2D_F64;
import org.apache.commons.io.FilenameUtils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates a printable Uchiya marker
 *
 * @author Peter Abeles
 */
public class CreateFiducialUchiya extends BaseFiducialSquare {
	@Option(name="-n",aliases = {"--DotsPerMarker"}, usage="Number of dots per marker")
	public int numberOfDots=30;

	@Option(name="-um",aliases = {"--UniqueMarkers"}, usage="Number of unique markers that it should create")
	public int totalUnique=1;

	@Option(name = "-dd", aliases = {"--Diameter"}, usage = "The diameter of each dot. In document units")
	public double dotDiameter =0.5;

	@Option(name = "-rs", aliases = {"--RandomSeed"}, usage = "Random seed used to create the markers")
	public long randomSeed=0xDEAD_BEEFL;

	@Option(name = "--DumpYaml", usage = "Save marker coordinates into a YAML file")
	public boolean dumpLocations = false;

	@Option(name = "--MarkerBorder", usage = "Draws a black line at the marker's border.")
	public boolean drawBorderLines = false;

	List<List<Point2D_F64>> markers = new ArrayList<>();

	@Override
	public void run() throws IOException {
		super.run();

		if( dumpLocations )
			saveMarkersToYaml();
	}

	/**
	 * Saves a description of all the markers in a YAML file so that it can be easily read later on
	 */
	private void saveMarkersToYaml() throws IOException {
		String nameYaml = FilenameUtils.getBaseName(fileName)+".yaml";
		FileWriter writer = new FileWriter(new File(new File(fileName).getParentFile(),nameYaml));

		var def = new UchiyaDefinition();
		def.randomSeed = randomSeed;
		def.dotDiameter = dotDiameter;
		def.maxDotsPerMarker = numberOfDots;
		def.markerWidth = markerWidth;
		def.units = unit.getAbbreviation();
		def.markers.addAll( markers );

		FiducialIO.saveUchiyaYaml(def,writer);
		writer.close();
	}

	@Override
	protected CreateFiducialDocumentImage createRendererImage(String filename) {
		var ret = new CreateUchiyaDocumentImage(filename);
		ret.dotDiameter = dotDiameter;
		return ret;
	}

	@Override
	protected CreateFiducialDocumentPDF createRendererPdf(String documentName, PaperSize paper, Unit units) {
		var ret = new CreateUchiyaDocumentPDF(documentName, paper, units);
		ret.dotDiameter = dotDiameter;
		ret.drawBorderLine = drawBorderLines;
		return ret;
	}

	@Override
	protected void callRenderPdf(CreateFiducialDocumentPDF renderer) throws IOException {
		((CreateUchiyaDocumentPDF)renderer).render(markers,numberOfDots,randomSeed);
	}

	@Override
	protected void callRenderImage(CreateFiducialDocumentImage renderer) {
		((CreateUchiyaDocumentImage)renderer).render(markers);
	}

	@Override
	public void finishParsing() {
		super.finishParsing();
		Random rand = new Random(randomSeed);
		for( int i = 0; i < totalUnique; i++ ) {
			List<Point2D_F64> marker = UchiyaMarkerGenerator.createRandomMarker(rand,
					numberOfDots, markerWidth, markerWidth,dotDiameter * 2.0);
			markers.add( marker );
		}
	}

	@Override
	protected void printHelp(CmdLineParser parser) {
		super.printHelp(parser);

		System.out.println("Creates 3 images in PNG format 500x500 pixels. Circles with a diameter of 21 pixels." +
				" Up to 32 dots per image");
		System.out.println("-w 500 -dd 21 -um 3 -n 32 -o uchiya.png");
		System.out.println();
		System.out.println("Creates a PDF document the fills in a grid with 4 markers, 8cm wide, 30 dots," +
				" and dumps a description to yaml");
		System.out.println("--MarkerBorder --DumpYaml -w 8 -um 4 -n 30 -o uchiya.pdf");
		System.out.println();
//		System.out.println("Opens a GUI");
//		System.out.println("--GUI");

		System.exit(-1);
	}

	public static void main(String[] args) {
		CreateFiducialUchiya generator = new CreateFiducialUchiya();
		CmdLineParser parser = new CmdLineParser(generator);

		if( args.length == 0 ) {
			generator.printHelp(parser);
		}

		try {
			parser.parseArgument(args);
			if( generator.guiMode ) {
				BoofSwingUtil.invokeNowOrLater(CreateFiducialSquareBinaryGui::new);
			} else {
				generator.finishParsing();
				generator.run();
			}
		} catch (CmdLineException e) {
			// handling of wrong arguments
			generator.printHelp(parser);
			System.err.println(e.getMessage());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
