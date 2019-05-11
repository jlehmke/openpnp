/*
 * Copyright (C) 2019 <mark@makr.zone>
 * based on the ReferenceStripFeeder 
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.machine.reference.feeder;



import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.Action;

import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.io.IOUtils;
import org.opencv.core.Mat;
import org.opencv.core.RotatedRect;
import org.opencv.imgproc.Imgproc;
import org.openpnp.ConfigurationListener;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.components.CameraView;
import org.openpnp.gui.components.CameraViewFilter;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.machine.reference.camera.BufferedImageCamera;
import org.openpnp.machine.reference.feeder.BlindsFeeder.FindFeatures;
import org.openpnp.machine.reference.feeder.wizards.BlindsFeederConfigurationWizard;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Point;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.NozzleTip;
import org.openpnp.spi.PropertySheetHolder;
import org.openpnp.util.HslColor;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.Utils2D;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.FluentCv;
import org.openpnp.vision.Ransac.Line;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;


/**
 * Implementation of Feeder that indexes through an array of cut tape strips held
 * by a 3D printed base. Each tape lane has a blinds style cover that can open/close by 
 * shifting half the pocket pitch. This only works for tapes where the pockets use up 
 * less than half the pocket pitch as is usually the case with punched paper carrier tapes 
 * but also for some plastic/embossed carrier tapes.  
 */


public class BlindsFeeder extends ReferenceFeeder {

    static public final Location nullLocation = new Location(LengthUnit.Millimeters);

    @Element(required = false)
    private Location fiducial1Location = new Location(LengthUnit.Millimeters);

    @Element(required = false)
    private Location fiducial2Location = new Location(LengthUnit.Millimeters);

    @Element(required = false)
    private Location fiducial3Location = new Location(LengthUnit.Millimeters);

    @Attribute(required = false)
    private boolean normalize = true;

    @Element(required = false)
    private Length tapeLength = new Length(0, LengthUnit.Millimeters);

    @Element(required = false)
    private Length feederExtent = new Length(0, LengthUnit.Millimeters);

    @Element(required = false)
    private Length pocketCenterline = new Length(0, LengthUnit.Millimeters);

    @Element(required = false)
    private Length pocketPitch = new Length(0, LengthUnit.Millimeters);

    @Element(required = false)
    private Length pocketSize = new Length(0, LengthUnit.Millimeters);

    @Element(required = false)
    private CvPipeline pipeline = createDefaultPipeline();

    @Attribute(required = false)
    private boolean visionEnabled = true;

    @Attribute
    private int feedCount = 0;

    @Attribute(required = false)
    private int feederNo = 0;

    @Attribute(required = false)
    private int feedersTotal = 0;

    @Attribute(required = false)
    private int pocketCount = 0;

    @Attribute(required = false)
    private int pocketsEmpty = 0;

    @Element(required = false)
    private Length sprocketPitch = new Length(4, LengthUnit.Millimeters);

    @Element(required = false)
    private Length edgeOpenDistance = new Length(2, LengthUnit.Millimeters); 
    
    @Element(required = false)
    private Length edgeClosedDistance = new Length(2, LengthUnit.Millimeters); 

    // we have 1mm push edge standard and the smallest CP40 nozzle tip has 0.8mm usable tip shaft
    @Element(required = false)
    private Length pushZOffset = new Length(0.25, LengthUnit.Millimeters); 

    @Attribute(required = false)
    private double pushSpeed = 0.1;

    @Attribute(required = false)
    private boolean pushHigh = false;
    
    // These internal setting are not on the GUI but can be changed in the XML.
    @Attribute(required = false)
    private int fidLocMaxPasses = 3;

    @Attribute(required = false)
    private double fidLocToleranceMillimeter = 0.5;

    @Attribute(required = false)
    private double pocketPosToleranceMillimeter = 0.1;

    // Transient stuff
    private Length pocketPosition = new Length(Double.NaN, LengthUnit.Millimeters);
    private Length pocketDistance = new Length(Double.NaN, LengthUnit.Millimeters);
    private boolean calibrating = false;
    private boolean calibrated = false;

    private void checkHomedState(Machine machine) {
        if (!machine.isHomed()) {
            this.setCalibrated(false);
        }
    }

    public BlindsFeeder() {
        // Listen to the machine become unhomed. Invalidate feeder calibration in this case. 
        // Note that home()  first switches the machine isHomed() state off, then on again, 
        // so we als catch re-homing. 
        Configuration.get().addListener(new ConfigurationListener.Adapter() {
            @Override
            public void configurationComplete(Configuration configuration) throws Exception {
                Configuration.get().getMachine().addListener(new MachineListener.Adapter() {

                    @Override
                    public void machineHeadActivity(Machine machine, Head head) {
                        checkHomedState(machine);
                    }

                    @Override
                    public void machineEnabled(Machine machine) {
                        checkHomedState(machine);
                    }
                });
            }
        });
    }


    private static int toInteger(double val) {
        return (int)Math.round(val);
    }

    private void recalculate() {
        // Geometry must correspond to 3D printed feeder.

        // The fiducials give us the first and last sprocket position directly (as a feeder-local X-coordinate).
        // The pockets are then aligned to the sprockets according to the EIA 481-C-2003 standard. 

        // According to the EIA standard, pockets align with the mid-point between two sprocket holes, however for the 
        // 2mm pitch tapes (0402 and smaller) there is another pocket squeezed in and aligned with the sprocket hole.
        // This means that for 2mm pitch parts there is one more pocket in the tape and the first one starts right on
        // the sprocket pitch instead of half the pocket pitch away. 
        boolean isSmallPitch = toInteger(sprocketPitch.divide(pocketPitch)) == 2;
        pocketCount = toInteger(Math.floor(tapeLength.divide(pocketPitch)))
                + (isSmallPitch ? 1 : 0); 
        // Align pocket center (which is at 0.25 x pocketPitch) to sprocketPitch
        Length pocketAlign = sprocketPitch
                .multiply(Math.floor(pocketPitch.multiply(0.25).divide(sprocketPitch))); 
        // Now shift that to a mid-point between two sprockets (unless it is a small pitch)
        pocketDistance = sprocketPitch.multiply(isSmallPitch ? 0.0 : 0.5)
                .add(pocketAlign); 
    }

    private void assertCalibration() throws Exception {
        // Make sure the feeder locations are calibrated, if vision is enabled. 
        if (isVisionEnabled()) {
            if (!isCalibrated()) {
                calibrateFeederLocations();
            }
        }
        recalculate();
    }

    public Location getPickLocation(double feedCount) {
        recalculate();

        // Calculate the next pick location in local feeder coordinates. 
        Length feederX = pocketPitch.multiply(feedCount-1.0).convertToUnits(location.getUnits()).add(pocketDistance);
        Length feederY = pocketCenterline.convertToUnits(location.getUnits());

        Location feederLocation = new Location(location.getUnits(), feederX.getValue(), feederY.getValue(), 
                location.getZ(), getTapeOrientationRotation());
        Location machineLocation = transformFeederToMachineLocation(feederLocation);
        return machineLocation;
    }

    @Override
    public Location getPickLocation() {
        return getPickLocation(this.feedCount);
    }

    public boolean isCoverOpenState(boolean openState) {
        if (coverType == CoverType.NoCover) {
            return openState == true;
        }
        if (Double.isNaN(pocketPosition.getValue())) {
            // Unknown means no
            return false;
        }
        if (coverType == CoverType.BlindsCover) {
            double positionError = Math.abs(pocketPosition.subtract(pocketDistance).convertToUnits(LengthUnit.Millimeters).getValue());
            return (positionError < pocketPosToleranceMillimeter) == openState;
        }
        if (coverType == CoverType.PushCover) {
            Length feederX = pocketPitch.multiply(feedCount).add(pocketDistance).convertToUnits(location.getUnits());
            return (pocketPosition.convertToUnits(location.getUnits()).getValue() > feederX.getValue()) == openState;
        }

        return false;
    }

    public boolean isCoverOpen() {
        return isCoverOpenState(true);
    }

    public boolean isCoverClosed() {
        return isCoverOpenState(false);
    }

    public boolean isCoverOpenChecked()  throws Exception {
        assertCalibration();
        if (Double.isNaN(pocketPosition.getValue())) {
            Camera camera = Configuration.get()
                    .getMachine()
                    .getDefaultHead()
                    .getDefaultCamera();
            Location cameraOpenPosition = getPickLocation(2);
            // Move the camera over the blinds to check the open position.
            MovableUtils.moveToLocationAtSafeZ(camera, cameraOpenPosition);
            findPocketPosition(camera);
            Logger.debug("[BlindsFeeder.isCoverOpenChecked] pocketPosition: {}, pocketDistance {}, error {}", 
                    pocketPosition, pocketDistance, pocketPosition.subtract(pocketDistance));
        }
        return isCoverOpen();
    }


    public void feed(Nozzle nozzle) throws Exception {
        if (getFeedCount() + getPocketsEmpty() >= getPocketCount()) {
            throw new Exception("Feeder "+getName()+" part "+getPart().getId()+" empty.");
        }
        
        assertCalibration();
        if (coverType == CoverType.BlindsCover) {
            if (coverActuation == CoverActuation.CheckOpen) {
                if (!isCoverOpenChecked()) {
                    // Invalidate position to measure again after user intervention.
                    pocketPosition = new Length(Double.NaN, LengthUnit.Millimeters);
                    throw new Exception("Feeder "+getName()+" "+getPart().getName()+": cover is not open. Please open manually.");
                }
            }
            else if (coverActuation == CoverActuation.OpenOnFirstUse) {
                if (!isCoverOpen()) {
                    actuateCover(true);
                }
            }
        }
        else if (coverType == CoverType.PushCover) {
            actuateCover(true);
        }
        // increase the feed count 
        setFeedCount(getFeedCount() + 1);
    }

    public class FindFeatures {
        private Camera camera;
        private CvPipeline pipeline;
        private long showResultMilliseconds;

        // recognized stuff
        private List<RotatedRect> blinds;
        private List<RotatedRect> fiducials;
        private List<Line> lines;
        private double pocketSizeMm;
        private double pocketPositionMm;
        private double pocketPitchMm;
        private double pocketCenterlineMm;

        public FindFeatures(Camera camera, CvPipeline pipeline, final long showResultMilliseconds) {
            this.camera = camera;
            this.pipeline = pipeline;
            this.showResultMilliseconds = showResultMilliseconds;
        }

        public List<RotatedRect> getBlinds() {
            return blinds;
        }
        public List<RotatedRect> getFiducials() {
            return fiducials;
        }
        public List<Line> getLines() {
            return lines;
        }
        public double getPocketSizeMm() {
            return pocketSizeMm;
        }
        public double getPocketPositionMm() {
            return pocketPositionMm;
        }
        public double getPocketPitchMm() {
            return pocketPitchMm;
        }
        public double getPocketCenterlineMm() {
            return pocketCenterlineMm;
        }

        private void drawRotatedRects(Mat mat, List<RotatedRect> features, Color color) {
            if (features == null || features.isEmpty()) {
                return;
            }
            Color centerColor = new HslColor(color).getComplementary();
            for (RotatedRect rect : features) {
                double x = rect.center.x;
                double y = rect.center.y;
                FluentCv.drawRotatedRect(mat, rect, color, 3);
                Imgproc.circle(mat, new org.opencv.core.Point(x, y), 2, FluentCv.colorToScalar(centerColor), 3);
            }
        }

        private void drawLines(Mat mat, List<Line> lines, Color color) {
            if (lines == null || lines.isEmpty()) {
                return;
            }
            for (Line line : lines) {
                Imgproc.line(mat, line.a, line.b, FluentCv.colorToScalar(color), 2);
            }
        }

        private double angleNorm(double angle) {
            while (angle > 45) {
                angle -= 90;
            }
            while (angle < -45) {
                angle += 90;
            }
            return angle;
        }

        // TODO: when PR #825 is merged, take Vision Utils method instead
        public Point getLocationPixels(Camera camera, Location location) {
            // get the units per pixel scale 
            Location unitsPerPixel = camera.getUnitsPerPixel();
            // convert inputs to the same units, center on camera and scale
            location = location.convertToUnits(unitsPerPixel.getUnits())
                    .subtract(camera.getLocation())
                    .multiply(1./unitsPerPixel.getX(), -1./unitsPerPixel.getY(), 0., 0.);
            // relative to upper left corner of camera in pixels
            return new Point(location.getX()+camera.getWidth()/2, location.getY()+camera.getHeight()/2);
        }
        
        /** 
         * Very simple histogram with zero knowledge about the range. 
         * Good only for low numbers of measurements.
         * The bins are filled with an 2-bin wide square kernel centered on the measurement, recording 
         * the overlap of the kernel with the bins. If the distribution of measurements clusters near the 
         * edge between two bins, dithering between the two, the recorded weight is still comparable to a 
         * similar distribution centered in the middle of a bin. Therefore the minimum/maximum is 
         * still reliably captured in these cases, even in the presence of background noise. 
         */
        class Histogram {
            private Map<Integer, Double> histogram = new HashMap<>();
            private Integer minimum = null;
            private double minimumVal = Double.NaN;
            private Integer maximum = null;
            private double maximumVal = Double.NaN;
            private double resolution;
            public Histogram(double resolution) {
                this.resolution = resolution;
            }
            void add(double key, double val) {
                // the three histogram bins around the key position
                int binY1 = (int)Math.round(key/resolution);   
                int binY0 = binY1-1; 
                int binY2 = binY1+1;
                // calculate the weight according to overlap with the kernel spreading 2 bins 
                double w0 = ((binY0+1.5)*resolution - key)/resolution;
                double w2 = (-(binY2-1.5)*resolution + key)/resolution;
                double w1 = 1.0;
                add(binY0, w0*val);
                add(binY1, w1*val);
                add(binY2, w2*val);
            }
            void add(int key, double val) {

                double newVal = histogram.get(key) == null ? val : histogram.get(key)+val;
                histogram.put(key, newVal);
                if (maximum == null || maximumVal < newVal) {
                    maximumVal = newVal;
                    maximum = key;
                }
                if (minimum == null || minimumVal > newVal) {
                    minimumVal = newVal;
                    minimum = key;
                }
            }
            public Map<Integer, Double> getHistogram() {
                return histogram;
            }
            public Integer getMinimum() {
                return minimum;
            }
            public double getMinimumKey() {
                return minimum == null ? Double.NaN : minimum*resolution;
            }
            public double getMinimumVal() {
                return minimumVal;
            }
            public Integer getMaximum() {
                return maximum;
            }
            public double getMaximumKey() {
                return maximum == null ? Double.NaN : maximum*resolution;
            }
            public double getMaximumVal() {
                return maximumVal;
            }
            public double getResolution() {
                return resolution;
            }
        }

        public FindFeatures invoke() throws Exception {
            List<RotatedRect> results = null;
            try {
                // Grab the results
                results = (List<RotatedRect>) pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME).model;
                if (results == null /*???|| results.isEmpty()*/) {
                    throw new Exception("Feeder " + getName() + ": No features found.");
                }
                // in accordance with EIA-481 etc. we use millimeters.
                Location mmScale = camera.getUnitsPerPixel().convertToUnits(LengthUnit.Millimeters);
                // TODO: configurable?
                final double fidMin = 1.6;
                final double fidMax = 2.4;
                final double fidAspect = 1.3; 
                // TODO: configurable?
                double blindMin = getPocketPitch().convertToUnits(LengthUnit.Millimeters).getValue()*0.5;
                double blindMax = getPocketSize().convertToUnits(LengthUnit.Millimeters).getValue();
                double blindAspect = 1.2*blindMax/blindMin;
                if (blindMax == 0) {
                    blindMax = 22;
                    blindAspect = 2;
                }
                if (blindMin == 0) {
                    blindMin = 0.5;
                    blindAspect = 3;
                }
                double positionTolerance = 5;
                double angleTolerance = 20;

                double pocketSizePreset = pocketSize.convertToUnits(LengthUnit.Millimeters).getValue();
                if (pocketSizePreset > 0) {
                    positionTolerance = pocketSizePreset*0.45; 
                }

                // Convert camera center.
                Location cameraFeederLocation = transformMachineToFeederLocation(camera.getLocation())
                        .convertToUnits(LengthUnit.Millimeters);
                double cameraFeederY = cameraFeederLocation.getY();  
                // Try to make sense of it.
                boolean angleTolerant = 
                        BlindsFeeder.nullLocation.equals(getFiducial1Location())
                        ||BlindsFeeder.nullLocation.equals(getFiducial2Location());
                boolean positionTolerant = angleTolerant;// || getPocketCenterline().getValue() == 0;
                blinds = new ArrayList<>();
                fiducials = new ArrayList<>();

                // Create center and corner histograms
                Histogram histogramUpper = new Histogram(0.1);
                Histogram histogramLower = new Histogram(0.1);

                for (RotatedRect result : results) {
                    org.opencv.core.Point points[] = new org.opencv.core.Point[4];
                    result.points(points);
                    boolean isAtMargin = false;
                    for (int i = 0; i < 4; i++) {
                        // Filter out rects sticking to the edges.
                        if (points[i].x <= 2 || points[i].x >= camera.getWidth() - 2
                                || points[i].y <= 2 || points[i].y >= camera.getHeight() - 2) {
                            isAtMargin = true;
                        }
                        // Next, please.
                        i++;
                    }
                    if (! isAtMargin) {
                        // Convert shape center.
                        Location center = transformMachineToFeederLocation(VisionUtils.getPixelLocation(camera, result.center.x, result.center.y));
                        double angle = transformPixelToFeederAngle(result.angle);
                        Location mmSize = mmScale.multiply(result.size.width, result.size.height, 0, 0);
                        if (positionTolerant || cameraFeederLocation.convertToUnits(LengthUnit.Millimeters).getLinearDistanceTo(center) < positionTolerance) {
                            if (angleTolerant || Math.abs(angleNorm(angle - 45)) < angleTolerance) {
                                if (mmSize.getX() > fidMin && mmSize.getX() < fidMax 
                                        && mmSize.getY() > fidMin && mmSize.getY() < fidMax
                                        && mmSize.getX()/mmSize.getY() < fidAspect 
                                        && mmSize.getY()/mmSize.getX() < fidAspect) {
                                    fiducials.add(result);
                                }
                                else {
                                    Logger.debug("[BlindsFeeder] dismissed fiducal candidate: result {}, mmSize {}", 
                                            result, mmSize);
                                }
                            }
                            else {
                                Logger.debug("[BlindsFeeder] dismissed fiducal candidate: result {}, angle {}", 
                                        result, angle);
                            }
                        }
                        else {
                            Logger.debug("[BlindsFeeder] dismissed fiducal candidate: result {}, center {}", 
                                    result, center);
                        }
                        if (positionTolerant || Math.abs(cameraFeederY - center.getY()) < positionTolerance) {
                            if (angleTolerant || Math.abs(angleNorm(angle - 0)) < angleTolerance) {
                                if (mmSize.getX() > blindMin && mmSize.getX() < blindMax && mmSize.getY() > blindMin && mmSize.getY() < blindMax
                                        && mmSize.getX()/mmSize.getY() < blindAspect 
                                        && mmSize.getY()/mmSize.getX() < blindAspect) {
                                    // Fits the size requirements.
                                    if (!positionTolerant) {
                                        // Add corners' Y to histogram for later pocket size analysis. 
                                        for (org.opencv.core.Point point : points) {
                                            Location corner = transformMachineToFeederLocation(VisionUtils.getPixelLocation(camera, point.x, point.y))
                                                    .convertToUnits(LengthUnit.Millimeters);
                                            if (corner.getY() < cameraFeederY) {
                                                histogramLower.add(corner.getY(), 1.);
                                            }
                                            else {
                                                histogramUpper.add(corner.getY(), 1.);
                                            }
                                        }
                                    }
                                    blinds.add(result);
                                }
                                else {
                                    Logger.debug("[BlindsFeeder] dismissed pocket candidate: result {}, mmSize {}", 
                                            result, mmSize);
                                }
                            }
                            else {
                                Logger.debug("[BlindsFeeder] dismissed pocket candidate: result {}, angle {}", 
                                        result, angle);
                            }
                        }
                        else {
                            Logger.debug("[BlindsFeeder] dismissed fiducal candidate: result {}, Y {}", 
                                    result, center.getY());
                        }
                    }
                }

                // Sort fiducials by distance from camera center.
                Collections.sort(fiducials, new Comparator<RotatedRect>() {
                    @Override
                    public int compare(RotatedRect o1, RotatedRect o2) {
                        double d1 = VisionUtils.getPixelLocation(camera, o1.center.x, o1.center.y).getLinearDistanceTo(camera.getLocation());
                        double d2 = VisionUtils.getPixelLocation(camera, o2.center.x, o2.center.y).getLinearDistanceTo(camera.getLocation());
                        return Double.compare(d1, d2);
                    }
                });

                // Sort blinds by feeder local X.
                Collections.sort(blinds, new Comparator<RotatedRect>() {
                    @Override
                    public int compare(RotatedRect o1, RotatedRect o2) {
                        double d1 = transformMachineToFeederLocation(VisionUtils.getPixelLocation(camera, o1.center.x, o1.center.y)).getX();
                        double d2 = transformMachineToFeederLocation(VisionUtils.getPixelLocation(camera, o2.center.x, o2.center.y)).getX();
                        return Double.compare(d1, d2);
                    }
                });


                // Try to determine the pocket size and center by evaluating the histogram of corner feeder local Y coordinates.
                double bestLowerY = histogramLower.getMaximumKey();
                double bestUpperY = histogramUpper.getMaximumKey();
                pocketSizeMm = bestUpperY - bestLowerY;
                pocketCenterlineMm = Math.round((bestUpperY + bestLowerY)*0.5);

                lines = new ArrayList<>();
                for (Double bestY : new double[]{bestLowerY, pocketCenterlineMm, bestUpperY}) {
                    if (!Double.isNaN(bestY)) {
                        // create a line for visual feedback
                        Location l1 = new Location(LengthUnit.Millimeters, -100., bestY, 0., 0.);
                        Location l2 = new Location(LengthUnit.Millimeters, +100., bestY, 0., 0.);
                        l1 = transformFeederToMachineLocation(l1);
                        l2 = transformFeederToMachineLocation(l2);
                        Point p1 = getLocationPixels(camera, l1);
                        Point p2 = getLocationPixels(camera, l2);
                        Line line = new Line(new org.opencv.core.Point(p1.x, p1.y), new org.opencv.core.Point(p2.x, p2.y));
                        lines.add(line);
                    }
                }

                // Try to determine the pocket pitch by creating a histogram of sorted blinds distances. 
                Location previous = null;
                Histogram histogramPitch = new Histogram(2);
                for (RotatedRect rect : blinds) {
                    Location location = transformMachineToFeederLocation(VisionUtils.getPixelLocation(camera, rect.center.x, rect.center.y))
                            .convertToUnits(LengthUnit.Millimeters);
                    if (previous != null) {
                        double pitch = location.getX() - previous.getX();
                        histogramPitch.add(pitch, 1.0);
                    }
                    previous = location;
                }
                pocketPitchMm = histogramPitch.getMaximumKey();

                // Try to determine the pocket position from feeder zero X.
                Histogram histogramDistance = new Histogram(0.1);
                for (RotatedRect rect : blinds) {
                    Location location = transformMachineToFeederLocation(VisionUtils.getPixelLocation(camera, rect.center.x, rect.center.y))
                            .convertToUnits(LengthUnit.Millimeters);
                    if (location.getX() < pocketPitchMm*3) {
                        // calculate the positive modulo distance
                        double position = location.getX() % pocketPitchMm;
                        if (position < 0.) {
                            position += pocketPitchMm;
                        }
                        // Simply record the position in three pitch-modulo bins to support wrap-around with kernel.  
                        histogramDistance.add(position-pocketPitchMm, 1.0);
                        histogramDistance.add(position, 1.0);
                        histogramDistance.add(position+pocketPitchMm, 1.0);
                    }
                }
                pocketPositionMm = histogramDistance.getMaximumKey();
                if (!Double.isNaN(pocketPositionMm)) {
                    if (pocketPositionMm < 0.) {
                        pocketPositionMm += pocketPitchMm;
                    }
                    if (pocketPositionMm > pocketPitchMm) {
                        pocketPositionMm -= pocketPitchMm;
                    }
                    // create a line for visual feedback
                    Location l1 = new Location(LengthUnit.Millimeters, pocketPositionMm, pocketCenterlineMm-100., 0., 0.);
                    Location l2 = new Location(LengthUnit.Millimeters, pocketPositionMm, pocketCenterlineMm+100., 0., 0.);
                    l1 = transformFeederToMachineLocation(l1);
                    l2 = transformFeederToMachineLocation(l2);
                    Point p1 = getLocationPixels(camera, l1);
                    Point p2 = getLocationPixels(camera, l2);
                    Line line = new Line(new org.opencv.core.Point(p1.x, p1.y), new org.opencv.core.Point(p2.x, p2.y));
                    lines.add(line);
                }

                if (showResultMilliseconds > 0) {
                    // Draw the result onto the pipeline image.
                    Mat resultMat = pipeline.getWorkingImage().clone();
                    drawRotatedRects(resultMat, getBlinds(), Color.blue);
                    drawRotatedRects(resultMat, getFiducials(), Color.white);
                    drawLines(resultMat, getLines(), Color.yellow);
                    BufferedImage showResult = OpenCvUtils.toBufferedImage(resultMat);
                    resultMat.release();
                    MainFrame.get().getCameraViews().getCameraView(camera)
                    .showFilteredImage(showResult, showResultMilliseconds);
                }
            }
            catch (ClassCastException e) {
                throw new Exception("Unrecognized result type (should be RotatedRect): " + results);
            }

            return this;
        }
    }

    public void findPocketsAndCenterline(Camera camera) throws Exception {
        if (coverType == CoverType.BlindsCover) {
            // For a BlindsCover we can use vision to try and determine the specs.
            try (CvPipeline pipeline = getCvPipeline(camera, true)) {

                // Reset the specs to allow FindFeatures to acquire them freely.
                setPocketCenterline(new Length(0., LengthUnit.Millimeters)); 
                setPocketPitch(new Length(0., LengthUnit.Millimeters)); 
                setPocketSize(new Length(0., LengthUnit.Millimeters)); 
                
                // Try to clone from another feeder.
                updateFromConnectedFeeder(camera.getLocation(), false);

                // Process vision
                pipeline.process();

                // Grab the results
                BlindsFeeder.FindFeatures findFeaturesResults = new FindFeatures(camera, pipeline, 1000).invoke();

                if (Double.isNaN(findFeaturesResults.getPocketCenterlineMm())) {
                    throw new Exception("Feeder " + getName() + ": Tape centerline not found.");
                }

                if (Double.isNaN(findFeaturesResults.getPocketPitchMm())) {
                    throw new Exception("Feeder " + getName() + ": Pocket pitch not found.");
                }
                // TODO: validate pitch against known EIA pitch values {2, 4, 8, 12, 16...}

                if (Double.isNaN(findFeaturesResults.getPocketSizeMm())) {
                    throw new Exception("Feeder " + getName() + ": Pocket size not found.");
                }

                setPocketCenterline(new Length(findFeaturesResults.getPocketCenterlineMm(), LengthUnit.Millimeters));
                setPocketPitch(new Length(findFeaturesResults.getPocketPitchMm(), LengthUnit.Millimeters));
                setPocketSize(new Length(findFeaturesResults.getPocketSizeMm(), LengthUnit.Millimeters));
            }
        }
        else  {
            // For other cover types we can only capture the pocket centerline from the camera position. 

            // Transform camera location to feeder local Millimeter Y coordinate rounded to 1mm. According to EIA-481-C and the design
            // of the 3D printed feeder we can assert all coordinates are integral 1mm values.
            Location cameraLocation = camera.getLocation();
            Location feederLocation = transformMachineToFeederLocation(cameraLocation).convertToUnits(LengthUnit.Millimeters);
            setPocketCenterline(new Length(Math.round(feederLocation.getY()), LengthUnit.Millimeters));
        }
    }

    public void findPocketPosition(Camera camera) throws Exception {
        location = camera.getLocation();
        try (CvPipeline pipeline = getCvPipeline(camera, true)) {
            // Move to the location
            MovableUtils.moveToLocationAtSafeZ(camera, location);

            // Process vision
            pipeline.process();

            // Grab the results
            BlindsFeeder.FindFeatures findFeaturesResults = new FindFeatures(camera, pipeline, 250).invoke();

            if (Double.isNaN(findFeaturesResults.getPocketPositionMm())) {
                throw new Exception("Feeder " + getName() + ": Pocket position not found.");
            }

            setPocketPosition(new Length(findFeaturesResults.getPocketPositionMm(), LengthUnit.Millimeters));
        }
    }

    public void calibrateCoverEdges()  throws Exception {
        if (coverType != CoverType.BlindsCover) {
            throw new Exception("Feeder " + getName() + ": Only Blinds Cover can be calibrated.");
        }
        Camera camera = Configuration.get()
                .getMachine()
                .getDefaultHead()
                .getDefaultCamera();
        
        // Calculate the wanted open/closed positions.
        assertCalibration();
        Length wantedOpenPosition = getPocketDistance();
        Length wantedClosedPosition = getPocketDistance().add(pocketPitch.multiply(0.5)).modulo(pocketPitch);
        Location cameraOpenPosition = getPickLocation(2);
        Location cameraClosedPosition = getPickLocation(1.5);
        
        double damping = 0.9;
        
        // Close cover to start with a known opposite position.
        actuateCover(true);

        // Calibration passes loop. 
        for (int i = 0; i < 3; i++) {
            // Open the cover.
            actuateCover(true);
            // Move the camera over the blinds to check the open position.
            MovableUtils.moveToLocationAtSafeZ(camera, cameraOpenPosition);
            // Where is it? 
            findPocketPosition(camera);
            Length offsetOpen = getPocketPosition().subtract(wantedOpenPosition).convertToUnits(LengthUnit.Millimeters);
            // Apply the error to the edge.
            setEdgeOpenDistance(getEdgeOpenDistance().add(offsetOpen.multiply(damping)));
            
            // Close the cover.
            actuateCover(false);
            // Move the camera over the blinds to check the closed position.
            MovableUtils.moveToLocationAtSafeZ(camera, cameraClosedPosition);
            // Where is it? 
            findPocketPosition(camera);
            Length offsetClosed = getPocketPosition().subtract(wantedClosedPosition).convertToUnits(LengthUnit.Millimeters);
            // Apply the error to the edge.
            setEdgeClosedDistance(getEdgeClosedDistance().subtract(offsetClosed.multiply(damping)));

            // Test against half the tolerance used for the "check open" test.
            if (Math.abs(offsetOpen.getValue()) < pocketPosToleranceMillimeter*0.5
                    && Math.abs(offsetClosed.getValue()) < pocketPosToleranceMillimeter*0.5) {
                // Both good enough - no more passes needed.
                break;
            }
        }
    }
    
    private Location locateFiducial(Camera camera, Location location) throws Exception {
        if (location.equals(nullLocation)) {
            throw new Exception("Feeder " + getName() + ": Fiducial location not set.");
        }

        // Take Z off the camera
        location = location.derive(camera.getLocation(), false, false, true, false);
        try (CvPipeline pipeline = getCvPipeline(camera, true)) {

            for (int i = 0; i < fidLocMaxPasses; i++) {
                // Move to the location
                MovableUtils.moveToLocationAtSafeZ(camera, location);

                //camera.settleAndCapture();
                // Process vision
                pipeline.process();

                // Interpret the results
                BlindsFeeder.FindFeatures findFeaturesResults = new FindFeatures(camera, pipeline, 250).invoke();
                List<RotatedRect> fiducials = findFeaturesResults.getFiducials();
                if (fiducials.isEmpty()) {
                    throw new Exception("Feeder " + getName() + ": Fiducial not found.");
                }
                // Convert location.
                RotatedRect bestFiducial = fiducials.get(0);
                Location bestFiducialLocation = VisionUtils.getPixelLocation(camera, bestFiducial.center.x, bestFiducial.center.y);
                double mmDistance = bestFiducialLocation.getLinearLengthTo(location).convertToUnits(LengthUnit.Millimeters).getValue();
                Logger.debug("[BlindsFeeder] bestFiducialLocation: {}, mmDistance {}", 
                        bestFiducialLocation, mmDistance);

                // update location
                location = bestFiducialLocation;

                if (mmDistance < fidLocToleranceMillimeter) {
                    // Already a good enough fix - skip further passes
                    break;
                }
            }
            // Return the final result.
            return location;
        }
    }

    public void calibrateFeederLocations() throws Exception {
        if (!isCalibrating()) {

            if ( !Configuration.get().getMachine().isHomed() ) {
                throw new Exception("Feeder " + getName() + ": Machine not yet homed.");
            }

            setCalibrating(true);
            try {
                Camera camera = Configuration.get()
                        .getMachine()
                        .getDefaultHead()
                        .getDefaultCamera();
                setFiducial1Location(locateFiducial(camera, getFiducial1Location()));
                setFiducial2Location(locateFiducial(camera, getFiducial2Location()));
                setFiducial3Location(locateFiducial(camera, getFiducial3Location()));
                setCalibrated(true);
            }
            finally {
                setCalibrating(false);
            }
        }
    }

    public enum CoverType {
        NoCover, BlindsCover, PushCover
    }
    public enum CoverActuation {
        Manual, CheckOpen, OpenOnFirstUse
    }

    @Attribute(required = false)
    private CoverType coverType = CoverType.BlindsCover;

    @Attribute(required = false)
    private CoverActuation coverActuation = CoverActuation.OpenOnFirstUse;

    public static void actuateAllFeederCovers(boolean openState) throws Exception  {
        // Filter out all the BlindsFeeders with blinds cover. 
        List<BlindsFeeder> feederList = new ArrayList<>();
        for (Feeder feeder : Configuration.get().getMachine().getFeeders())  {
            if (feeder instanceof BlindsFeeder) {
                BlindsFeeder blindsFeeder = (BlindsFeeder)feeder;
                if (blindsFeeder.coverType == CoverType.BlindsCover) {
                    if (!blindsFeeder.isCoverOpenState(openState)) {
                        feederList.add(blindsFeeder);
                    }
                }
            }
        }

        // Sort feeders by position
        Collections.sort(feederList, new Comparator<BlindsFeeder>() {
            @Override
            public int compare(BlindsFeeder o1, BlindsFeeder o2) {
                Location l1 = o1.getPickLocation(0).convertToUnits(LengthUnit.Millimeters);
                Location l2 = o2.getPickLocation(0).convertToUnits(LengthUnit.Millimeters);
                // Sort in bands by X.
                int xBand1 = (int)(l1.getX()/100.0);
                int xBand2 = (int)(l2.getX()/100.0);
                if (Integer.compare(xBand1, xBand2) != 0) {
                    return Integer.compare(xBand1, xBand2);
                }
                // Same band - sort by Y. 
                return Double.compare(l1.getY(), l2.getY());    
            }
        });

        // Finally actuate the feeders
        for (BlindsFeeder blindsFeeder : feederList) {
            blindsFeeder.actuateCover(openState);
        }
    }
    
    public double getTapeOrientationRotation() {
        // Our local feeder coordinate system has the positive X axis advance the pick location. Y is the direction in which 
        // the feeders are arrayed. With the feeders arranged around and the first parts oriented towards the PCB, this results 
        // in a counter-clockwise layout of the feeders when seen from above (like pins on an electronics part). 
        // Consequently the sprocket holes are at the bottom when seen in an upright Cartesian coordinate system, which 
        // corresponds to a feeder on the right side of the machine, which again corresponds to 0 degrees in a Cartesian coordinate 
        // system.
        // 
        // However in an automatic feeder it is actually the tape that is advanced and the positive "X" direction is seen the 
        // other way. Consequently the sprocket holes are usually shown on top (i.e. in EIA-481-C) and this was adopted as 0 degrees 
        // in the OpenPNP ReferenceStripFeeder. 
        // 
        // This means that we need to rotate by 180 degrees from our feeder local coordinate system to have the same understanding. 
        return location.getRotation() + 180.;
    }

    public void actuateCover(boolean openState) throws Exception {
        if (coverType == CoverType.NoCover) {
            throw new Exception("Feeder " + getName() + ": has no cover to actuate.");
        }
        else {
            Nozzle nozzle = MainFrame.get().getMachineControls().getSelectedNozzle();
            NozzleTip nozzleTip = (nozzle == null ? null : nozzle.getNozzleTip());
            if (nozzleTip == null) {
                throw new Exception("Feeder " + getName() + ": no nozzle tip loaded.");
            }
            Length nozzleTipDiameter = pushHigh ? nozzleTip.getDiameterHigh() : nozzleTip.getDiameterLow(); 
            if (nozzleTipDiameter.getValue() == 0.) {
                throw new Exception("Feeder " + getName() + ": current nozzle tip "+nozzleTip.getId()+" has push diameter not set. Check the nozzle tip configuration.");
            }

            assertCalibration();

            if (coverType == CoverType.BlindsCover) {
                // Calculate the motion for the cover to be pushed in feeder local coordinates. 
                Length feederX0 = (openState ? 
                        edgeOpenDistance.multiply(-1.0)
                        .subtract(pocketPitch) // half pitch too far back
                        .subtract(nozzleTipDiameter.multiply(0.5)) 
                        : 
                            edgeClosedDistance
                            .add(tapeLength) 
                            .add(pocketPitch) // half pitch too far back
                            .add(nozzleTipDiameter.multiply(0.5)))
                        .convertToUnits(location.getUnits());
                Length feederX1 = (openState ? 
                        edgeOpenDistance.multiply(-1.0).subtract(nozzleTipDiameter.multiply(0.5)) : 
                            edgeClosedDistance.add(tapeLength).add(nozzleTipDiameter.multiply(0.5)))
                        .convertToUnits(location.getUnits());
                Length feederY = pocketCenterline
                        .convertToUnits(location.getUnits());
                Length feederZ = location.getLengthZ().add(pushZOffset);

                Location feederLocation0 = new Location(location.getUnits(), feederX0.getValue(), feederY.getValue(), feederZ.getValue(), getTapeOrientationRotation());
                Location feederLocation1 = new Location(location.getUnits(), feederX1.getValue(), feederY.getValue(), feederZ.getValue(), getTapeOrientationRotation());

                // Convert to machine locations
                Location machineLocation0 = transformFeederToMachineLocation(feederLocation0);
                Location machineLocation1 = transformFeederToMachineLocation(feederLocation1);

                // Execute the motion
                MovableUtils.moveToLocationAtSafeZ(nozzle, machineLocation0);
                nozzle.moveTo(machineLocation1, nozzle.getHead().getMachine().getSpeed()*pushSpeed);
                nozzle.getHead().moveToSafeZ();
                // Store the new pocket position.
                setPocketPosition(openState ? pocketDistance : pocketDistance.subtract(pocketPitch.multiply(0.5)));
            }
            else if (coverType == CoverType.PushCover && openState) {
                // Calculate the motion for the cover to be pushed in feeder local coordinates.
                Location pickLocation = getPickLocation(getFeedCount()+1);
                Location pickFeederLocation = transformMachineToFeederLocation(pickLocation);
                Length feederX0 = (getFeedCount() == 0 || !isCoverOpen()?
                        pocketPitch.multiply(-0.5)
                        .subtract(nozzleTipDiameter.multiply(1))
                        :
                            pickFeederLocation.getLengthX()
                            .subtract(pocketPitch.multiply(0.5))  
                            .subtract(pocketDistance) // at least half sprocket pitch too far back and far enough for closed/aligned cover
                            .subtract(nozzleTipDiameter.multiply(1)))
                        .convertToUnits(location.getUnits());
                Length feederX1 = pickFeederLocation.getLengthX()
                        .add(pocketPitch.multiply(0.5))
                        .subtract(nozzleTipDiameter.multiply(0.5))
                        .convertToUnits(location.getUnits());
                Length feederY = pickFeederLocation.getLengthY()
                        .convertToUnits(location.getUnits());
                Length feederZ = location.getLengthZ().subtract(pushZOffset);

                Location feederLocation0 = new Location(location.getUnits(), feederX0.getValue(), feederY.getValue(), feederZ.getValue(), getTapeOrientationRotation());
                Location feederLocation1 = new Location(location.getUnits(), feederX1.getValue(), feederY.getValue(), feederZ.getValue(), getTapeOrientationRotation());

                // Convert to machine locations
                Location machineLocation0 = transformFeederToMachineLocation(feederLocation0);
                Location machineLocation1 = transformFeederToMachineLocation(feederLocation1);

                // Execute the motion
                MovableUtils.moveToLocationAtSafeZ(nozzle, machineLocation0);
                nozzle.moveTo(machineLocation1, nozzle.getHead().getMachine().getSpeed()*pushSpeed);
                nozzle.moveTo(pickLocation.derive(machineLocation1,  false, false, true, false));
                // do not: nozzle.getHead().moveToSafeZ();
                // Store the newly uncovered pocket position.
                setPocketPosition(pickFeederLocation.getLengthX());
            }
        }
    }

    /**
     * The Machine to Feeder transformation and inverse.
     */
    private AffineTransform tx;
    private AffineTransform txInverse;
    private double txRotation;


    public void invalidateFeederTransformation() {
        tx = null;
        txInverse = null;
    }

    /**
     * Generates the transformation from feeder local coordinate system to 
     * machine coordinates as defined by the fiducials.
     * @return
     */
    private boolean updateFeederToMachineTransform() {
        // Make sure the inverse will be regenerated.
        txInverse = null;
        // Get some basics. 
        Location origin = fiducial1Location;
        double mm = new Length(1, LengthUnit.Millimeters).convertToUnits(origin.getUnits()).getValue();
        double distance = origin.getLinearDistanceTo(fiducial2Location);
        // Check sanity.
        if (nullLocation.equals(fiducial1Location) 
                || nullLocation.equals(fiducial2Location) 
                || distance < 1*mm) {
            // Some fiducials not set yet or invalid - just take the unity transform for now.  
            tx = new AffineTransform();
            // Translate for fiducial 1 (if set).
            tx.translate(origin.getX(), origin.getY());
            txRotation = 0.;
            return false;
        }
        if (!normalize) {
            // We know the fiducial distance should be a multiple of 2mm as it is aligned with the 
            // sprockets in the 3D printed model. 
            distance = Math.round(distance/2/mm)*2*mm;
        }
        // Update the tape length to rounded distance.
        setTapeLength(new Length(Math.round(distance/2/mm)*2*mm, origin.getUnits()));

        Location axisX;
        Location axisY; 
        axisX = fiducial2Location.convertToUnits(origin.getUnits()).subtract(origin).multiply(1/distance, 1/distance, 0, 0);

        // Fiducial 3 may be the one across from fiducial 1 or 2 - the nearer one is it. 
        Location ref = (fiducial3Location.convertToUnits(origin.getUnits()).getLinearDistanceTo(fiducial2Location) 
                < fiducial3Location.convertToUnits(origin.getUnits()).getLinearDistanceTo(fiducial1Location) ?
                fiducial2Location : fiducial1Location).convertToUnits(origin.getUnits());
        distance = fiducial3Location.equals(nullLocation) ? 0 : ref.getLinearDistanceTo(fiducial3Location);
        if (normalize || distance < 1*mm) {
            // We want to normalize or fiducial 3 is not set or has no distance. 
            // Take the cross product of the X axis to form the Y axis (i.e. the fiducial 3 is ignored for the axis).
            axisY = new Location(axisX.getUnits(), -axisX.getY(), axisX.getX(), 0, 0);
            // Fiducial 3 can still serve to get the extent of the overall feeder.
            setFeederExtent(new Length(Math.round(distance/mm)*mm, origin.getUnits()));
        }
        else {
            // We know the fiducial distance should be a multiple of 1mm as this is enforced in the 3D printed model. 
            distance = Math.round(distance/mm)*mm;
            axisY = fiducial3Location.convertToUnits(origin.getUnits()).subtract(ref).multiply(1/distance, 1/distance, 0, 0);
            // Fiducial 3 also serves to get the extent of the overall feeder.
            setFeederExtent(new Length(distance, origin.getUnits()));
        }
        // Finally create the transformation.  
        tx = new AffineTransform(
                axisX.getX(), axisX.getY(), 
                axisY.getX(), axisY.getY(), 
                origin.getX(), origin.getY());
        // Reconstruct the angle at which the feeder lays on the machine. When "normalize" is off, it is only
        // an approximation valid for lines parallel to the tapes (as is most useful). It ignores shear in the
        // transform.
        txRotation = Math.toDegrees(Math.atan2(axisX.getX(), axisX.getY()));
        return true;
    }

    AffineTransform getFeederToMachineTransform() {
        if (tx == null) {
            updateFeederToMachineTransform();
        }
        return tx;
    }

    double getFeederToMachineRotation() {
        if (tx == null) {
            updateFeederToMachineTransform();
        }
        return txRotation;
    }

    double getMachineToFeederRotation() {
        if (tx == null) {
            updateFeederToMachineTransform();
        }
        return -txRotation;
    }

    private AffineTransform getMachineToFeederTransform() {
        if (txInverse == null) {
            txInverse = new AffineTransform(getFeederToMachineTransform());
            try {
                txInverse.invert();
            }
            catch (NoninvertibleTransformException e) {
                // Should really never happen, as getFeederToMachineTransform() falls back to benign transforms.   
                // Just take the unity transform for now. 
                txInverse = new AffineTransform();
                // Translate from fiducial 1 (if set).
                txInverse.translate(-fiducial1Location.getX(), -fiducial1Location.getY());
            }
        }
        return txInverse;
    }

    public Location transformFeederToMachineLocation(Location feederLocation) {
        AffineTransform tx = getFeederToMachineTransform();
        double rotation = getFeederToMachineRotation();
        feederLocation = feederLocation.convertToUnits(fiducial1Location.getUnits()); 
        Point2D.Double ptDst = new Point2D.Double();
        tx.transform(new Point2D.Double(feederLocation.getX(), feederLocation.getY()), ptDst);
        return new Location(fiducial1Location.getUnits(), ptDst.getX(), ptDst.getY(), 
                feederLocation.getZ(), feederLocation.getRotation()+rotation);
    }

    public Location transformMachineToFeederLocation(Location machineLocation) {
        AffineTransform tx = getMachineToFeederTransform();
        double rotation = getMachineToFeederRotation();
        machineLocation = machineLocation.convertToUnits(fiducial1Location.getUnits()); 
        Point2D.Double ptDst = new Point2D.Double();
        tx.transform(new Point2D.Double(machineLocation.getX(), machineLocation.getY()), ptDst);
        return new Location(fiducial1Location.getUnits(), ptDst.getX(), ptDst.getY(), 
                machineLocation.getZ(), machineLocation.getRotation()+rotation);
    }

    public double transformFeederToMachineAngle(double angle) {
        return angle+getFeederToMachineRotation();
    }
    public double transformMachineToFeederAngle(double angle) {
        // We don't need th reverse transform, a simple sign reversion will do.
        return angle+getMachineToFeederRotation();
    }
    public double transformPixelToFeederAngle(double angle) {
        // Pixel angles are left-handed, coming from the OpenCV coordinate system, where 
        // Z points away from the viewer whereas in OpenPNP the opposite is true. 
        return -transformMachineToFeederAngle(angle);
    }

    public boolean isLocationInFeeder(Location location, boolean fiducial1MatchOnly) {
        // First check if it is a fiducial 1 match.  
        if (nullLocation.equals(fiducial1Location)) {
            // Never match a uninizialized fiducial.
            return false;
        }
        if (fiducial1Location.convertToUnits(LengthUnit.Millimeters).getLinearDistanceTo(location) < 2) {
            // Direct fiducial 1 match with tolerance.
            return true;
        }
        else if (fiducial1MatchOnly) {
            // No fiducial 1 match but one is required.
            return false;
        }
        // No match on the fiducial 1, so check whole feeder holder area.	
        Location feederLocation = transformMachineToFeederLocation(location);
        double mm = new Length(1, LengthUnit.Millimeters).convertToUnits(feederLocation.getUnits()).getValue();
        if (feederLocation.getX() >= -1*mm && feederLocation.getX() <= tapeLength.convertToUnits(feederLocation.getUnits()).getValue() + 1*mm) {
            if (feederLocation.getY() >= -1*mm && feederLocation.getY() <= feederExtent.getValue() + 1*mm) {
                return true;
            }
        }
        return false;
    }

    public static List<BlindsFeeder> getConnectedFeedersByLocation(Location location, boolean fiducial1MatchOnly) {
        // Get all the feeders with connected by location.
        List<BlindsFeeder> list = new ArrayList<>();
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (feeder instanceof BlindsFeeder) {
                BlindsFeeder blindsFeeder = (BlindsFeeder) feeder;
                if (blindsFeeder.isLocationInFeeder(location, fiducial1MatchOnly)) {
                    list.add(blindsFeeder);
                }
            }
        }
        // Sort by feeder tape centerline.
        Collections.sort(list, new Comparator<BlindsFeeder>() {
            @Override
            public int compare(BlindsFeeder feeder1, BlindsFeeder feeder2)  {
                return new Double(feeder1.getPocketCenterline().getValue())
                        .compareTo(feeder2.getPocketCenterline().convertToUnits(feeder1.getPocketCenterline().getUnits()).getValue());
            }
        });
        return list;
    }

    public List<BlindsFeeder> getConnectedFeeders() {
        // Get all the feeders with the same fiducial 1 location.
        return getConnectedFeedersByLocation(fiducial1Location, true);
    }

    private boolean isUpdating = false;

    public void updateFromConnectedFeeder(BlindsFeeder feeder) {
        if (this != feeder && ! isUpdating) {
            isUpdating = true;
            setFiducial1Location(feeder.fiducial1Location);
            setFiducial2Location(feeder.fiducial2Location);
            setFiducial3Location(feeder.fiducial3Location);
            setTapeLength(feeder.tapeLength);
            setFeederExtent(feeder.feederExtent);
            setNormalize(feeder.normalize);
            if (this.pocketCenterline.equals(feeder.pocketCenterline)) {
                // The tape is shared by two feeders -> update the pocket position (open/close state) and calibrated edges.
                // NOTE: the user is responsible to ensure a non-overlapping feedCount/emptyPocketCount range 
                setPocketPosition(feeder.pocketPosition);
                setEdgeOpenDistance(feeder.edgeOpenDistance);
                setEdgeClosedDistance(feeder.edgeClosedDistance);
              }
            setCalibrated(feeder.calibrated);
            isUpdating = false;
        }
    }

    private void updateTapeNumbering()    {
        // Renumber the feeder tape lanes.
        List<BlindsFeeder> list = getConnectedFeedersByLocation(fiducial1Location, true);
        int feedersTotal = list.size();
        int feederNo = 0;
        for (BlindsFeeder feeder : list) {
            feeder.setFeedersTotal(feedersTotal);
            feeder.setFeederNo(++feederNo);
        }
    }

    public boolean updateFromConnectedFeeder(Location location, boolean fiducial1MatchOnly) {
        boolean hasMatch = false;
        for (BlindsFeeder feeder : getConnectedFeedersByLocation(location, fiducial1MatchOnly)) {
            if (feeder != this) {
                updateFromConnectedFeeder(feeder);
                hasMatch = true;
                break;
            }
        }
        if (! nullLocation.equals(fiducial1Location)) {
            // Now that we have the (partial) coordinate system we can calculate the tape pocket centerline.
            Location feederLocation = transformMachineToFeederLocation(location);
            double mm = new Length(1, LengthUnit.Millimeters).convertToUnits(feederLocation.getUnits()).getValue();
            // Take the nearest integer Millimeter value. 
            this.setPocketCenterline(new Length(Math.round(feederLocation.getY()/mm)*mm, feederLocation.getUnits()));
        }
        updateTapeNumbering();
        return hasMatch;
    }

    public void updateConnectedFeedersFromThis(Location location, boolean fiducial1MatchOnly) {
        if (! isUpdating) {
            isUpdating = true;
            // Transform might have changed.
            updateFeederToMachineTransform();
            // Update all the feeders on the same 3D printed holder from this.
            for (BlindsFeeder feeder : getConnectedFeedersByLocation(location, fiducial1MatchOnly)) {
                if (feeder != this) {
                    feeder.updateFromConnectedFeeder(this);
                }
            }
            isUpdating = false;
        }
    }
    public void updateConnectedFeedersFromThis() {
        updateConnectedFeedersFromThis(fiducial1Location, true);
    }

    public CvPipeline getPipeline() {
        return pipeline;
    }

    public void resetPipeline() {
        pipeline = createDefaultPipeline();
    }

    public void setPipelineToAllFeeders() throws CloneNotSupportedException {
        // Update all the feeders' pipeline on the same 3D printed holder from this.
        for (BlindsFeeder feeder : getConnectedFeedersByLocation(fiducial1Location, true)) {
            if (feeder != this) {
                feeder.pipeline = pipeline.clone();
            }
        }
    }

    public CvPipeline getCvPipeline(Camera camera, boolean clone) {
        try {
            CvPipeline pipeline = getPipeline();
            if (clone) {
                pipeline = pipeline.clone();
            }
            pipeline.setProperty("camera", camera);
            pipeline.setProperty("feeder", this);

            /* this won't work, properties must be hardwired in the stages
             * 
             * // Provide pixel min/max area to pipeline.
            // We restrict this to 24mm tape carrier having a 20.1mm max pocket size.
            // See ANSI/EIA-48 1 -C p. 11.
            double mm = VisionUtils.toPixels(new Length(1, LengthUnit.Millimeters), camera);
            Integer minArea = (int) (0.8*mm*1*mm); // 2mm pitch punched paper carrier tape (0402). 
            Integer maxArea = (int) (21*mm*21*mm); // 24mm tape carrier.
            // feeder specific
            double nominalArea = VisionUtils.toPixels(feeder.getPocketPitch(), camera)
             *VisionUtils.toPixels(feeder.getPocketSize(), camera);
            Integer minArea = (int) (0.75*nominalArea); 
            Integer maxArea = (int) (1.25*nominalArea); 
            pipeline.setProperty("FilterContours.minArea", minArea);
            pipeline.setProperty("FilterContours.maxArea", maxArea);
             */
            return pipeline;
        }
        catch (CloneNotSupportedException e) {
            throw new Error(e);
        }
    }



    public boolean isCalibrating() {
        return calibrating;
    }

    private void setCalibrating(boolean calibrating) {
        this.calibrating = calibrating;
    }

    public boolean isCalibrated() {
        return calibrated;
    }

    private void setCalibrated(boolean calibrated) {
        boolean oldValue = this.calibrated;
        this.calibrated = calibrated;
        if (oldValue != calibrated) {
            this.updateConnectedFeedersFromThis();
            firePropertyChange("calibrated", oldValue, calibrated);
        }
    }

    public Location getFiducial1Location() {
        return fiducial1Location;
    }

    public void setFiducial1Location(Location fiducial1Location) {
        Location oldValue = this.fiducial1Location;
        this.fiducial1Location = fiducial1Location;
        if (! oldValue.equals(fiducial1Location)) {
            this.invalidateFeederTransformation();
            firePropertyChange("fiducial1Location", oldValue, fiducial1Location);
            if (oldValue.equals(nullLocation) 
                    && fiducial2Location.equals(nullLocation)
                    && fiducial3Location.equals(nullLocation)) {
                // That's an initial fix. Try to clone from another feeder.
                updateFromConnectedFeeder(fiducial1Location, true);
            }
            else {
                this.updateConnectedFeedersFromThis(oldValue, true);
                if ((! oldValue.equals(nullLocation))
                        && oldValue.convertToUnits(LengthUnit.Millimeters).getLinearDistanceTo(fiducial1Location) > 4) {
                    // Large change in fiducial 1 location - move fiducials 2 and 3 as well (i.e. move the whole feeder).
                    if (! fiducial2Location.equals(nullLocation)) {
                        setFiducial2Location(fiducial2Location.add(fiducial1Location.subtract(oldValue)));
                    }
                    if (! fiducial3Location.equals(nullLocation)) {
                        setFiducial3Location(fiducial3Location.add(fiducial1Location.subtract(oldValue)));
                    }
                }
            }
        }
    }


    public Location getFiducial2Location() {
        return fiducial2Location;
    }


    public void setFiducial2Location(Location fiducial2Location) {
        Location oldValue = this.fiducial2Location;
        this.fiducial2Location = fiducial2Location;
        if (! oldValue.equals(fiducial2Location)) {
            this.invalidateFeederTransformation();
            this.updateConnectedFeedersFromThis();
            firePropertyChange("fiducial2Location", oldValue, fiducial2Location);
        }
    }


    public Location getFiducial3Location() {
        return fiducial3Location;
    }


    public void setFiducial3Location(Location fiducial3Location) {
        Location oldValue = this.fiducial3Location;
        this.fiducial3Location = fiducial3Location;
        if (! oldValue.equals(fiducial3Location)) {
            this.invalidateFeederTransformation();
            this.updateConnectedFeedersFromThis();
            firePropertyChange("fiducial3Location", oldValue, fiducial3Location);
        }
    }


    public boolean isNormalize() {
        return normalize;
    }


    public void setNormalize(boolean normalize) {
        boolean oldValue = this.normalize;
        this.normalize = normalize;
        if (oldValue != normalize) {
            this.invalidateFeederTransformation();
            this.updateConnectedFeedersFromThis();
            firePropertyChange("normalize", oldValue, normalize);
        }
    }


    public Length getTapeLength() {
        return tapeLength;
    }


    public void setTapeLength(Length tapeLength) {
        Length oldValue = this.tapeLength;
        this.tapeLength = tapeLength;
        if (! oldValue.equals(tapeLength)) {
            this.updateConnectedFeedersFromThis();
            firePropertyChange("tapeLength", oldValue, tapeLength);
        }
    }


    public Length getFeederExtent() {
        return feederExtent;
    }


    public void setFeederExtent(Length feederExtent) {
        Length oldValue = this.feederExtent;
        this.feederExtent = feederExtent;
        if (! oldValue.equals(feederExtent)) {
            this.updateConnectedFeedersFromThis();
            firePropertyChange("feederExtent", oldValue, feederExtent);
        }
    }


    public Length getPocketCenterline() {
        return pocketCenterline;
    }


    public void setPocketCenterline(Length pocketCenterline) {
        Length oldValue = this.pocketCenterline;
        this.pocketCenterline = pocketCenterline;
        firePropertyChange("pocketCenterline", oldValue, pocketCenterline);
        updateTapeNumbering();
    }


    public Length getPocketPitch() {
        return pocketPitch;
    }


    public void setPocketPitch(Length pocketPitch) {
        Length oldValue = this.pocketPitch;
        this.pocketPitch = pocketPitch;
        firePropertyChange("pocketPitch", oldValue, pocketPitch);
    }


    public Length getPocketSize() {
        return pocketSize;
    }


    public void setPocketSize(Length pocketSize) {
        Length oldValue = this.pocketSize;
        this.pocketSize = pocketSize;
        firePropertyChange("pocketSize", oldValue, pocketSize);
    }

    public int getPocketCount() {
        return pocketCount;
    }

    public void setPocketCount(int pocketCount) {
        int oldValue = this.pocketCount;
        this.pocketCount = pocketCount;
        firePropertyChange("pocketCount", oldValue, pocketCount);
    }

    public int getPocketsEmpty() {
        return pocketsEmpty;
    }

    public void setPocketsEmpty(int pocketsEmpty) {
        int oldValue = this.pocketsEmpty;
        this.pocketsEmpty = pocketsEmpty;
        firePropertyChange("pocketsEmpty", oldValue, pocketsEmpty);
    }

    public Length getPocketDistance() {
        return pocketDistance;
    }

    public void setPocketDistance(Length pocketDistance) {
        Length oldValue = this.pocketDistance;
        this.pocketDistance = pocketDistance;
        firePropertyChange("pocketDistance", oldValue, pocketDistance);
    }

    public Length getPocketPosition() {
        return pocketPosition;
    }

    public void setPocketPosition(Length pocketPosition) {
        Length oldValue = this.pocketPosition;
        this.pocketPosition = pocketPosition;
        if (!(oldValue.equals(pocketPosition)
                || Double.isNaN(oldValue.getValue()) == Double.isNaN(pocketPosition.getValue()))) {
            firePropertyChange("pocketPosition", oldValue, pocketPosition);
            this.updateConnectedFeedersFromThis();
        }
    }

    public int getFeedCount() {
        return feedCount;
    }

    public void setFeedCount(int feedCount) {
        int oldValue = this.feedCount;
        this.feedCount = feedCount;
        //this.visionOffsets = null;
        firePropertyChange("feedCount", oldValue, feedCount);
    }

    public boolean isVisionEnabled() {
        return visionEnabled;
    }

    public void setVisionEnabled(boolean visionEnabled) {
        boolean oldValue = this.visionEnabled;
        this.visionEnabled = visionEnabled;
        firePropertyChange("visionEnabled", oldValue, visionEnabled);
    }


    public int getFeederNo() {
        return feederNo;
    }


    public void setFeederNo(int feederNo) {
        int oldValue = this.feederNo;
        this.feederNo = feederNo;
        firePropertyChange("feederNo", oldValue, feederNo);
    }


    public int getFeedersTotal() {
        return feedersTotal;
    }

    public Length getEdgeOpenDistance() {
        return edgeOpenDistance;
    }

    public void setEdgeOpenDistance(Length edgeOpenDistance) {
        Length oldValue = this.edgeOpenDistance;
        this.edgeOpenDistance = edgeOpenDistance;
        if (! oldValue.equals(edgeOpenDistance)) {
            this.updateConnectedFeedersFromThis();
            firePropertyChange("edgeOpenDistance", oldValue, edgeOpenDistance);
        }
    }

    public Length getEdgeClosedDistance() {
        return edgeClosedDistance;
    }

    public void setEdgeClosedDistance(Length edgeClosedDistance) {
        Length oldValue = this.edgeClosedDistance;
        this.edgeClosedDistance = edgeClosedDistance;
        if (! oldValue.equals(edgeClosedDistance)) {
            this.updateConnectedFeedersFromThis();
            firePropertyChange("edgeClosedDistance", oldValue, edgeClosedDistance);
        }
    }

    public double getPushSpeed() {
        return pushSpeed;
    }

    public void setPushSpeed(double pushSpeed) {
        this.pushSpeed = pushSpeed;
    }

    public Length getPushZOffset() {
        return pushZOffset;
    }

    public void setPushZOffset(Length pushZOffset) {
        Length oldValue = this.pushZOffset;
        this.pushZOffset = pushZOffset;
        firePropertyChange("pushZOffset", oldValue, pushZOffset);
    }
    
    

    public boolean isPushHigh() {
        return pushHigh;
    }

    public void setPushHigh(boolean pushHigh) {
        boolean oldValue = this.pushHigh;
        this.pushHigh = pushHigh;
        firePropertyChange("pushHigh", oldValue, pushHigh);
    }

    public CoverType getCoverType() {
        return coverType;
    }

    public void setCoverType(CoverType coverType) {
        this.coverType = coverType;
    }

    public CoverActuation getCoverActuation() {
        return coverActuation;
    }

    public void setCoverActuation(CoverActuation coverActuation) {
        this.coverActuation = coverActuation;
    }

    public void setFeedersTotal(int feedersTotal) {
        int oldValue = this.feedersTotal;
        this.feedersTotal = feedersTotal;
        firePropertyChange("feedersTotal", oldValue, feedersTotal);
    }


    @Override
    public String toString() {
        return getName();
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new BlindsFeederConfigurationWizard(this);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return getClass().getSimpleName() + " " + getName();
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        return null;
    }

    private static CvPipeline createDefaultPipeline() {
        try {
            String xml = IOUtils.toString(BlindsFeeder.class
                    .getResource("BlindsFeeder-DefaultPipeline.xml"));
            return new CvPipeline(xml);
        }
        catch (Exception e) {
            throw new Error(e);
        }
    }

}

