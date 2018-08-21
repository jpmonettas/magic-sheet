package utils;
import javafx.scene.control.TableCell;

public class CellUtils { 
	public static TableCell makeTableCell (){
		return new TableCell() {
			@Override
			protected void updateItem(Object item, boolean empty) {
				super.updateItem(item, empty);

				if (item == null || empty) {
					setText(null);
					setStyle("");
				} else {
					setText(item.toString());
				}
			}
		};
		
	} 
}
