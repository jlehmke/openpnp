package org.openpnp.model;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.MaxRotation;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PartSettings;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PartSizeCheckMethod;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PreRotateUsage;
import org.openpnp.machine.reference.vision.wizards.BottomVisionSettingsConfigurationWizard;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

public class BottomVisionSettings extends AbstractVisionSettings {

    @Attribute(required = false)
    protected PreRotateUsage preRotateUsage = PreRotateUsage.Default;

    @Attribute(required = false)
    protected PartSizeCheckMethod checkPartSizeMethod = PartSizeCheckMethod.Disabled;

    @Attribute(required = false)
    protected int checkSizeTolerancePercent = 20;

    @Attribute(required = false)
    protected int padShrinkPercent = 0;

    @Attribute(required = false)
    protected boolean cutToBodySize = false;

    @Attribute(required = false)
    protected MaxRotation maxRotation = MaxRotation.Adjust;

    @Attribute(required = false)
    protected boolean asymmetric = false;

    @Element(required = false)
    protected Location visionOffset = new Location(LengthUnit.Millimeters);

    @Override
    public Wizard getConfigurationWizard() {
        return new BottomVisionSettingsConfigurationWizard(this, null);
    }

    public Wizard getConfigurationWizard(PartSettingsHolder settingsHolder) {
        return new BottomVisionSettingsConfigurationWizard(this, settingsHolder);
    }

    public BottomVisionSettings() {
        super(Configuration.createId("BVS"));
    }

    public BottomVisionSettings(String id) {
        super(id);
    }

    public BottomVisionSettings(PartSettings partSettings) {
        this();
        this.setEnabled(partSettings.isEnabled());
        this.setPipeline(partSettings.getPipeline());
        this.preRotateUsage = partSettings.getPreRotateUsage();
        this.checkPartSizeMethod = partSettings.getCheckPartSizeMethod();
        this.checkSizeTolerancePercent = partSettings.getCheckSizeTolerancePercent();
        this.maxRotation = partSettings.getMaxRotation();
        this.visionOffset = partSettings.getVisionOffset();
        this.asymmetric = this.visionOffset.isInitialized();
    }

    public PreRotateUsage getPreRotateUsage() {
        return preRotateUsage;
    }

    public void setPreRotateUsage(PreRotateUsage preRotateUsage) {
        Object oldValue = this.preRotateUsage;
        this.preRotateUsage = preRotateUsage;
        firePropertyChange("preRotateUsage", oldValue, preRotateUsage);
    }

    public PartSizeCheckMethod getCheckPartSizeMethod() {
        return checkPartSizeMethod;
    }

    public void setCheckPartSizeMethod(PartSizeCheckMethod checkPartSizeMethod) {
        Object oldValue = this.checkPartSizeMethod;
        this.checkPartSizeMethod = checkPartSizeMethod;
        firePropertyChange("checkPartSizeMethod", oldValue, checkPartSizeMethod);
    }

    public int getCheckSizeTolerancePercent() {
        return checkSizeTolerancePercent;
    }

    public void setCheckSizeTolerancePercent(int checkSizeTolerancePercent) {
        Object oldValue = this.checkSizeTolerancePercent;
        this.checkSizeTolerancePercent = checkSizeTolerancePercent;
        firePropertyChange("checkSizeTolerancePercent", oldValue, checkSizeTolerancePercent);
    }

    public int getPadShrinkPercent() {
        return padShrinkPercent;
    }

    public void setPadShrinkPercent(int padShrinkPercent) {
        Object oldValue = this.padShrinkPercent;
        this.padShrinkPercent = padShrinkPercent;
        firePropertyChange("padShrinkPercent", oldValue, padShrinkPercent);
    }

    public boolean isCutToBodySize() {
        return cutToBodySize;
    }

    public void setCutToBodySize(boolean cutToBodySize) {
        Object oldValue = this.cutToBodySize;
        this.cutToBodySize = cutToBodySize;
        firePropertyChange("cutToBodySize", oldValue, cutToBodySize);
    }

    public MaxRotation getMaxRotation() {
        return maxRotation;
    }

    public void setMaxRotation(MaxRotation maxRotation) {
        Object oldValue = this.maxRotation;
        this.maxRotation = maxRotation;
        firePropertyChange("maxRotation", oldValue, maxRotation);
    }

    public boolean isAsymmetric() {
        if (visionOffset.isInitialized()) {
            // Where offsets were stored from previous versions, make it asymmetric.
            asymmetric = true;
        }
        return asymmetric;
    }

    public void setAsymmetric(boolean asymmetric) {
        Object oldValue = this.asymmetric;
        this.asymmetric = asymmetric;
        if (!asymmetric) {
            // Reset the offsets.
            this.setVisionOffset(new Location(LengthUnit.Millimeters));
        }
        firePropertyChange("asymmetric", oldValue, this.asymmetric);
    }

    public Location getVisionOffset() {
        return visionOffset;
    }

    public void setVisionOffset(Location visionOffset) {
        Object oldValue = this.visionOffset;
        this.visionOffset = visionOffset.derive(null, null, 0.0, 0.0);
        firePropertyChange("visionOffset", oldValue, this.visionOffset);
    }

    public void setValues(BottomVisionSettings another) {
        setEnabled(another.isEnabled());
        try {
            setPipeline(another.getPipeline().clone());
        }
        catch (CloneNotSupportedException e) {
        }
        setPipelineParameterAssignments(another.getPipelineParameterAssignments());
        setPreRotateUsage(another.getPreRotateUsage());
        setCheckPartSizeMethod(another.checkPartSizeMethod);
        setMaxRotation(another.getMaxRotation());
        setCheckSizeTolerancePercent(another.getCheckSizeTolerancePercent());
        setPadShrinkPercent(another.getPadShrinkPercent());
        setCutToBodySize(another.isCutToBodySize());
        setVisionOffset(another.getVisionOffset());
        setAsymmetric(another.isAsymmetric());
        Configuration.get().fireVisionSettingsChanged();
    }

    @Override
    public void resetToDefault() {
        // Reset to stock settings.
        BottomVisionSettings stockVisionSettings = (BottomVisionSettings) Configuration.get()
                .getVisionSettings(AbstractVisionSettings.STOCK_BOTTOM_ID);
        setValues(stockVisionSettings);
    }

    /**
     * Returns a new Footprint whose pads are transformed to estimate the real pin/lead geometry:
     * each pad is shrunk by {@code padShrinkPercent} (dimensions and corner radius reduced
     * accordingly), and optionally clipped to the body outline when {@code cutToBodySize} is set.
     * Returns null when neither option is active or the footprint has no pads.
     */
    public Footprint getEstimatedFootprint(Footprint footprint) {
        // Determine effective clip half-dimensions: overall (if set) overrides body; body used when cutToBodySize.
        double clipHalfW = footprint.getOverallWidth() > 0 ? footprint.getOverallWidth() / 2.0
                : (cutToBodySize && footprint.getBodyWidth() > 0 ? footprint.getBodyWidth() / 2.0 : 0);
        double clipHalfH = footprint.getOverallHeight() > 0 ? footprint.getOverallHeight() / 2.0
                : (cutToBodySize && footprint.getBodyHeight() > 0 ? footprint.getBodyHeight() / 2.0 : 0);
        boolean hasClip = clipHalfW > 0 && clipHalfH > 0;

        if (padShrinkPercent <= 0 && !hasClip) {
            return null;
        }
        List<Footprint.Pad> sourcePads = footprint.getPads();
        if (sourcePads.isEmpty()) {
            return null;
        }

        double shrink = padShrinkPercent / 100.0;

        List<Footprint.Pad> estimatedPads = new ArrayList<>();
        for (Footprint.Pad pad : sourcePads) {
            double w = pad.getWidth();
            double h = pad.getHeight();
            double cx = pad.getX();
            double cy = pad.getY();
            double roundness = pad.getRoundness(); // percent of min(w,h)

            // --- Size decrement ---
            // Corner radius: r = min(w,h) * roundness/100
            // After shrinking by S%, each side is reduced by min(w,h)*S/100 total across both ends,
            // so the corner radius reduces by min(w,h)*S/100.
            // r_new = max(0, r_orig - min(w,h)*S/100) = max(0, min(w,h)*(roundness-S)/100)
            double absCornerRadius = 0;
            if (padShrinkPercent > 0) {
                double origMin = Math.min(w, h);
                absCornerRadius = Math.max(0.0, origMin * (roundness - padShrinkPercent) / 100.0);
                w = w * (1.0 - shrink);
                h = h * (1.0 - shrink);
                // Recompute roundness from absolute corner radius and new dimensions
                double newMin = Math.min(w, h);
                roundness = newMin > 0 ? Math.min(100.0, absCornerRadius / newMin * 100.0) : 0;
            }
            else {
                absCornerRadius = Math.min(w, h) * roundness / 100.0;
            }

            // --- Cut to overall/body outline ---
            // Clip in pad-local coordinates: rotate clip rect into pad-local space,
            // compute its bounding box there, then clip.
            if (hasClip && w > 0 && h > 0) {
                double theta = Math.toRadians(pad.getRotation());
                double cosT = Math.cos(theta);
                double sinT = Math.sin(theta);
                // Clip-rect corners in global space, relative to pad center:
                double[] bxs = {-clipHalfW - cx,  clipHalfW - cx,  clipHalfW - cx, -clipHalfW - cx};
                double[] bys = {-clipHalfH - cy, -clipHalfH - cy,  clipHalfH - cy,  clipHalfH - cy};
                // Transform to pad-local (inverse rotate by theta):
                double localMinX = Double.MAX_VALUE, localMaxX = -Double.MAX_VALUE;
                double localMinY = Double.MAX_VALUE, localMaxY = -Double.MAX_VALUE;
                for (int i = 0; i < 4; i++) {
                    double lx =  cosT * bxs[i] + sinT * bys[i];
                    double ly = -sinT * bxs[i] + cosT * bys[i];
                    localMinX = Math.min(localMinX, lx);
                    localMaxX = Math.max(localMaxX, lx);
                    localMinY = Math.min(localMinY, ly);
                    localMaxY = Math.max(localMaxY, ly);
                }
                // Clip pad (centred at 0 in local space) against the transformed body box:
                double clippedL = Math.max(-w / 2.0, localMinX);
                double clippedR = Math.min( w / 2.0, localMaxX);
                double clippedB = Math.max(-h / 2.0, localMinY);
                double clippedT = Math.min( h / 2.0, localMaxY);
                if (clippedL >= clippedR || clippedB >= clippedT) {
                    continue; // pad fully outside body — skip
                }
                double clippedW = clippedR - clippedL;
                double clippedH = clippedT - clippedB;
                // Local offset of the new pad centre relative to old pad centre:
                double localDX = (clippedL + clippedR) / 2.0;
                double localDY = (clippedB + clippedT) / 2.0;
                // Rotate offset back to global:
                cx += cosT * localDX - sinT * localDY;
                cy += sinT * localDX + cosT * localDY;
                w = clippedW;
                h = clippedH;
                // Cap corner radius to new dimensions
                double newMin = Math.min(w, h);
                absCornerRadius = Math.min(absCornerRadius, newMin / 2.0);
                roundness = newMin > 0 ? Math.min(100.0, absCornerRadius / newMin * 100.0) : 0;
            }

            if (w <= 0 || h <= 0) {
                continue;
            }
            Footprint.Pad ep = new Footprint.Pad();
            ep.setName(pad.getName());
            ep.setX(cx);
            ep.setY(cy);
            ep.setWidth(w);
            ep.setHeight(h);
            ep.setRotation(pad.getRotation());
            ep.setRoundness(roundness);
            estimatedPads.add(ep);
        }

        if (estimatedPads.isEmpty()) {
            return null;
        }
        Footprint estimated = new Footprint();
        estimated.setUnits(footprint.getUnits());
        estimated.setBodyWidth(footprint.getBodyWidth());
        estimated.setBodyHeight(footprint.getBodyHeight());
        for (Footprint.Pad ep : estimatedPads) {
            estimated.addPad(ep);
        }
        return estimated;
    }

    public Location getPartCheckSize(Part part, boolean addTolerance) {
        Footprint footprint = part.getPackage().getFootprint();
        double checkWidth = 0.0;
        double checkHeight = 0.0;

        switch (checkPartSizeMethod) {
            case Disabled:
                return null;
            case BodySize:
                checkWidth = footprint.getBodyWidth();
                checkHeight = footprint.getBodyHeight();
                break;
            case PadExtents:
                Footprint estimated = getEstimatedFootprint(footprint);
                Footprint source = estimated != null ? estimated : footprint;
                java.awt.geom.Rectangle2D bounds = source.getPadsShape().getBounds2D();
                checkWidth = bounds.getWidth();
                checkHeight = bounds.getHeight();
                break;
        }
        double factor = addTolerance ? checkSizeTolerancePercent * 0.01 + 1.0 : 1.0;
        return new Location(footprint.getUnits(), checkWidth * factor, checkHeight * factor, 0, 0);
    }

}
