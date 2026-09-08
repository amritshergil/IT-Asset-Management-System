import javafx.beans.property.*;

public class Software {
    private final IntegerProperty softwareId;
    private final StringProperty softwareName;
    private final StringProperty softwareVersion;
    private final StringProperty manufacturer;
    private final IntegerProperty assetId;

    public Software(int softwareId, String softwareName, String version, String manufacturer, int assetId) {
        this.softwareId = new SimpleIntegerProperty(softwareId);
        this.softwareName = new SimpleStringProperty(softwareName);
        this.softwareVersion = new SimpleStringProperty(version);
        this.manufacturer = new SimpleStringProperty(manufacturer);
        this.assetId = new SimpleIntegerProperty(assetId);
    }

    public int getSoftwareId() { return softwareId.get(); }
    public IntegerProperty softwareIdProperty() { return softwareId; }

    public String getSoftwareName() { return softwareName.get(); }
    public StringProperty softwareNameProperty() { return softwareName; }

    public String getSoftwareVersion() { return softwareVersion.get(); }
    public StringProperty softwareVersionProperty() { return softwareVersion; }

    public String getManufacturer() { return manufacturer.get(); }
    public StringProperty manufacturerProperty() { return manufacturer; }

    public int getAssetId() { return assetId.get(); }
    public IntegerProperty assetIdProperty() { return assetId; }
}
