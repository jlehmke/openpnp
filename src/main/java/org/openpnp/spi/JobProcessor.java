package org.openpnp.spi;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.openpnp.model.Job;

public interface JobProcessor extends PropertySheetHolder, WizardConfigurable {
    public interface TextStatusListener {
        public void textStatus(String text);
    }

    public void initialize(Job job) throws Exception;

    public boolean next() throws JobProcessorException;

    boolean isSteppingToNextMotion();

    public void abort() throws JobProcessorException;    

    public void addTextStatusListener(TextStatusListener listener);

    public void removeTextStatusListener(TextStatusListener listener);
    
    public class JobProcessorException extends Exception {
        private static final long serialVersionUID = 1L;

        private final Object source;
        private Object secondarySource = null;
        private boolean interrupting = false;

        private static String getThrowableMessage(Throwable throwable) {
            if (throwable.getMessage() != null) {
                return throwable.getMessage();
            }
            // If a message is missing, use the stack trace as the message
            // (same behavior as MessageBoxes.errorBox() when given an exception directly).
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            return sw.toString();
        }

        /**
         * Inherit the interrupting state and secondarySource from the first JobProcessorException in the
         * chain of causes.
         * 
         * @param exception
         * @param throwable
         */
        private static void inheritInterruptingAndSecondarySource(JobProcessorException exception, final Throwable throwable) {
            // loop the chain to throwables/causes
            for (Throwable t = throwable; t != null; t = t.getCause()) {
                if (t instanceof JobProcessorException) {
                    JobProcessorException e = ((JobProcessorException) t);
                    exception.interrupting = e.isInterrupting();
                    exception.secondarySource = e.secondarySource;
                    // leave the loop here at the first JobProcessorException
                    break;
                }
            }
        }
        
        public JobProcessorException(Object source, Throwable throwable) {
            super(getThrowableMessage(throwable), throwable);
            this.source = source;
            inheritInterruptingAndSecondarySource(this, throwable);
        }

        public JobProcessorException(Object source, Object secondarySource, Throwable throwable) {
            super(getThrowableMessage(throwable), throwable);
            this.source = source;
            inheritInterruptingAndSecondarySource(this, throwable);
            this.secondarySource = secondarySource;
        }

        public JobProcessorException(Object source, String message) {
            super(message);
            this.source = source;
        }

        public JobProcessorException(Object source, Object secondarySource, String message) {
            super(message);
            this.source = source;
            this.secondarySource = source;
        }

        public JobProcessorException(Object source, String message, boolean interrupting) {
            super(message);
            this.source = source;
            this.interrupting = interrupting;
        }

        public JobProcessorException(Object source, Throwable throwable, boolean interrupting) {
            super(getThrowableMessage(throwable), throwable);
            this.source = source;
            this.interrupting = interrupting;
        }

        public Object getSource() {
            return source;
        }

        public Object getSecondarySource() {
            return secondarySource;
        }

        public boolean isInterrupting() {
            return interrupting;
        }
    }
}
