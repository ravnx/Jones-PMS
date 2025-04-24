package model.print;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;


public class LabelPrintable implements Printable {

	private String labelFileName;
	
	public LabelPrintable(String labelFileName) {
		this.labelFileName = labelFileName;
	}
	
	@Override
    public int print(Graphics graphics, PageFormat pageFormat, 
        int pageIndex) throws PrinterException {  
		        
        int result = NO_SUCH_PAGE;
        
        Graphics2D graphics2d = (Graphics2D)graphics;
        graphics2d.setColor(Color.BLACK);
        graphics2d.setBackground(Color.WHITE);
                
        if (pageIndex == 0) {
            // translate to avoid clipping
            graphics2d.translate((int) pageFormat.getImageableX(), 
                (int) pageFormat.getImageableY());   
            
			try {
				BufferedImage read = ImageIO.read(new File(labelFileName));
				
				double width = pageFormat.getImageableWidth();
				double height = pageFormat.getImageableHeight();

				// Note: drawImage(img, x, y, width, height, observer) was not working on mac!
				AffineTransform t = new AffineTransform();
				t.scale(width / read.getWidth(), height / read.getHeight());
				graphics2d.drawImage(read, new AffineTransformOp(t, AffineTransformOp.TYPE_BILINEAR), 0, 0);
			} catch (IOException e) {
				e.printStackTrace();
				return result;
			}
            
            result = PAGE_EXISTS;    
        }    
        return result;    
    }
}
