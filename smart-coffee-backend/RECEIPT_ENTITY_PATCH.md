# Receipt required patch
Add to Receipt.java:
```java
public String snapshot(){ return snapshot; }
public void issued(String key){ this.objectKey=key; this.status=ReceiptStatus.ISSUED; this.issuedAt=Instant.now(); }
public void failed(){ this.status=ReceiptStatus.FAILED; }
```
Replace `SimpleReceiptDocumentRenderer` with a real PDF renderer before production.
