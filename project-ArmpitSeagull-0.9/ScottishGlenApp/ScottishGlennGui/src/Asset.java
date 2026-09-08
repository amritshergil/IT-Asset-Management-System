import javafx.beans.property.*;

public class Asset {

    private final IntegerProperty assetId;
    private final IntegerProperty employeeId;
    private final StringProperty purchaseDate;
    private final StringProperty notes;

    private final StringProperty systemName;
    private final StringProperty model;
    private final StringProperty manufacturer;
    private final StringProperty deviceType;
    private final StringProperty ipAddress;

    // Constructor for new assets
    public Asset(int employeeId, String purchaseDate, String notes,
                 String systemName, String model, String manufacturer,
                 String deviceType, String ipAddress) {

        this.assetId = new SimpleIntegerProperty(0); // thois is a placeholder until DB assigns ID
        this.employeeId = new SimpleIntegerProperty(employeeId);
        this.purchaseDate = new SimpleStringProperty(purchaseDate);
        this.notes = new SimpleStringProperty(notes);

        this.systemName = new SimpleStringProperty(systemName);
        this.model = new SimpleStringProperty(model);
        this.manufacturer = new SimpleStringProperty(manufacturer);
        this.deviceType = new SimpleStringProperty(deviceType);
        this.ipAddress = new SimpleStringProperty(ipAddress);
    }

    // Constructor for loading existing assets from DB
    public Asset(int assetId, String purchaseDate, String notes, int employeeId,
             String systemName, String model, String manufacturer,
             String deviceType, String ipAddress) {

        this.assetId = new SimpleIntegerProperty(assetId);
        this.purchaseDate = new SimpleStringProperty(purchaseDate);
        this.notes = new SimpleStringProperty(notes);
        this.employeeId = new SimpleIntegerProperty(employeeId);

        this.systemName = new SimpleStringProperty(systemName);
        this.model = new SimpleStringProperty(model);
        this.manufacturer = new SimpleStringProperty(manufacturer);
        this.deviceType = new SimpleStringProperty(deviceType);
        this.ipAddress = new SimpleStringProperty(ipAddress);
    }


    //Getters 
    public int getAssetId() { return assetId.get(); }
    public int getEmployeeId() { return employeeId.get(); }
    public String getPurchaseDate() { return purchaseDate.get(); }
    public String getNotes() { return notes.get(); }
    public String getSystemName() { return systemName.get(); }
    public String getModel() { return model.get(); }
    public String getManufacturer() { return manufacturer.get(); }
    public String getDeviceType() { return deviceType.get(); }
    public String getIpAddress() { return ipAddress.get(); }

    //Property getters 
    public IntegerProperty assetIdProperty() { return assetId; }
    public IntegerProperty employeeIdProperty() { return employeeId; }
    public StringProperty purchaseDateProperty() { return purchaseDate; }
    public StringProperty notesProperty() { return notes; }
    public StringProperty systemNameProperty() { return systemName; }
    public StringProperty modelProperty() { return model; }
    public StringProperty manufacturerProperty() { return manufacturer; }
    public StringProperty deviceTypeProperty() { return deviceType; }
    public StringProperty ipAddressProperty() { return ipAddress; }

    //Setters
    public void setAssetId(int id) { this.assetId.set(id); }
    public void setEmployeeId(int id) { this.employeeId.set(id); }
    public void setPurchaseDate(String date) { this.purchaseDate.set(date); }
    public void setNotes(String notes) { this.notes.set(notes); }
    public void setSystemName(String v) { this.systemName.set(v); }
    public void setModel(String v) { this.model.set(v); }
    public void setManufacturer(String v) { this.manufacturer.set(v); }
    public void setDeviceType(String v) { this.deviceType.set(v); }
    public void setIpAddress(String v) { this.ipAddress.set(v); }
}
