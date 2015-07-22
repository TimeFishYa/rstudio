/*
 * SourceWindowManager.java
 *
 * Copyright (C) 2009-15 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.views.source;

import java.util.ArrayList;
import java.util.HashMap;

import org.rstudio.core.client.JsArrayUtil;
import org.rstudio.core.client.Pair;
import org.rstudio.core.client.Point;
import org.rstudio.core.client.SerializedCommand;
import org.rstudio.core.client.SerializedCommandQueue;
import org.rstudio.core.client.Size;
import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.dom.WindowEx;
import org.rstudio.core.client.js.JsObject;
import org.rstudio.core.client.widget.OperationWithInput;
import org.rstudio.studio.client.application.Desktop;
import org.rstudio.studio.client.application.events.CrossWindowEvent;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.common.GlobalDisplay;
import org.rstudio.studio.client.common.filetypes.FileTypeRegistry;
import org.rstudio.studio.client.common.satellite.Satellite;
import org.rstudio.studio.client.common.satellite.SatelliteManager;
import org.rstudio.studio.client.common.satellite.events.AllSatellitesClosingEvent;
import org.rstudio.studio.client.common.satellite.events.SatelliteClosedEvent;
import org.rstudio.studio.client.common.satellite.model.SatelliteWindowGeometry;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.server.Void;
import org.rstudio.studio.client.server.VoidServerRequestCallback;
import org.rstudio.studio.client.shiny.events.ShinyApplicationStatusEvent;
import org.rstudio.studio.client.workbench.WorkbenchContext;
import org.rstudio.studio.client.workbench.model.ClientState;
import org.rstudio.studio.client.workbench.model.Session;
import org.rstudio.studio.client.workbench.model.UnsavedChangesItem;
import org.rstudio.studio.client.workbench.model.UnsavedChangesTarget;
import org.rstudio.studio.client.workbench.model.helper.JSObjectStateValue;
import org.rstudio.studio.client.workbench.views.help.events.ShowHelpEvent;
import org.rstudio.studio.client.workbench.views.help.events.ShowHelpHandler;
import org.rstudio.studio.client.workbench.views.source.events.DocTabClosedEvent;
import org.rstudio.studio.client.workbench.views.source.events.DocTabDragStartedEvent;
import org.rstudio.studio.client.workbench.views.source.events.DocWindowChangedEvent;
import org.rstudio.studio.client.workbench.views.source.events.PopoutDocEvent;
import org.rstudio.studio.client.workbench.views.source.events.SourceDocAddedEvent;
import org.rstudio.studio.client.workbench.views.source.model.SourceDocument;
import org.rstudio.studio.client.workbench.views.source.model.SourceServerOperations;
import org.rstudio.studio.client.workbench.views.source.model.SourceWindowParams;

import com.google.gwt.core.client.JsArray;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.Window;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

@Singleton
public class SourceWindowManager implements PopoutDocEvent.Handler,
                                            SourceDocAddedEvent.Handler,
                                            SatelliteClosedEvent.Handler,
                                            DocTabDragStartedEvent.Handler,
                                            DocWindowChangedEvent.Handler,
                                            DocTabClosedEvent.Handler,
                                            AllSatellitesClosingEvent.Handler,
                                            ShinyApplicationStatusEvent.Handler,
                                            ShowHelpHandler
{
   @Inject
   public SourceWindowManager(
         Provider<SatelliteManager> pSatelliteManager, 
         Provider<Satellite> pSatellite,
         Provider<WorkbenchContext> pWorkbenchContext,
         SourceServerOperations server,
         EventBus events,
         FileTypeRegistry registry,
         GlobalDisplay display, 
         SourceShim sourceShim,
         Session session)
   {
      events_ = events;
      server_ = server;
      pSatelliteManager_ = pSatelliteManager;
      pSatellite_ = pSatellite;
      pWorkbenchContext_ = pWorkbenchContext;
      display_ = display;
      
      events_.addHandler(DocWindowChangedEvent.TYPE, this);
      
      if (isMainSourceWindow())
      {
         // most event handlers only make sense on the main window
         events_.addHandler(PopoutDocEvent.TYPE, this);
         events_.addHandler(DocTabDragStartedEvent.TYPE, this);
         events_.addHandler(ShinyApplicationStatusEvent.TYPE, this);
         events_.addHandler(AllSatellitesClosingEvent.TYPE, this);
         events_.addHandler(SourceDocAddedEvent.TYPE, this);
         events_.addHandler(SatelliteClosedEvent.TYPE, this);
         events_.addHandler(DocTabClosedEvent.TYPE, this);
         events_.addHandler(ShowHelpEvent.TYPE, this);

         JsArray<SourceDocument> docs = 
               session.getSessionInfo().getSourceDocuments();
         sourceDocs_ = docs;

         exportFromMain();
         
         new JSObjectStateValue(
                 "source-window",
                 "sourceWindowGeometry",
                 ClientState.PROJECT_PERSISTENT,
                 session.getSessionInfo().getClientState(),
                 false)
         {
            @Override
            protected void onInit(JsObject value)
            {
               // save the window geometries 
               if (value != null)
                  windowGeometry_ = value;

               // compute the max ordinal value in the geometry set
               JsArrayString windowIds = windowGeometry_.keys();
               for (int i = 0; i < windowIds.length(); i++) 
               {
                  SatelliteWindowGeometry geometry = 
                        windowGeometry_.getObject(windowIds.get(i)).cast();
                  maxOrdinal_ = Math.max(geometry.getOrdinal(), maxOrdinal_);
               }
            }

            @Override
            protected JsObject getValue()
            {
               return windowGeometry_;
            }
            
            @Override
            protected boolean hasChanged()
            {
               return updateWindowGeometry();
            }
         };
         
         // open this session's source windows
         for (int i = 0; i < docs.length(); i++)
         {
            String windowId = docs.get(i).getSourceWindowId();
            if (!StringUtil.isNullOrEmpty(windowId) &&
                !isSourceWindowOpen(windowId))
            {
               openSourceWindow(windowId, null);
            }
         }
      }
   }

   // Public methods ----------------------------------------------------------
   
   public String getSourceWindowId()
   {
      return sourceWindowId(Window.Location.getParameter("view"));
   }
   
   public int getSourceWindowOrdinal()
   {
      return thisWindowOrdinal_;
   }
   
   public void setSourceWindowOrdinal(int ordinal)
   {
      thisWindowOrdinal_ = ordinal;
   }
   
   public boolean isMainSourceWindow()
   {
      return !pSatellite_.get().isCurrentWindowSatellite();
   }
   
   public JsArray<SourceDocument> getSourceDocs()
   {
      if (isMainSourceWindow())
         return sourceDocs_;
      else
         return getMainWindowSourceDocs();
   }
   
   public boolean isSourceWindowOpen(String windowId)
   {
      return sourceWindows_.containsKey(windowId);
   }
   
   public String getWindowIdOfDocPath(String path)
   {
      JsArray<SourceDocument> docs = getSourceDocs();
      for (int i = 0; i < docs.length(); i++)
      {
         if (docs.get(i).getPath() != null && 
             docs.get(i).getPath().equals(path))
         {
            String windowId = docs.get(i).getSourceWindowId();
            if (windowId != null)
               return windowId;
            else
               return "";
         }
      }
      return null;
   }
   
   public String getWindowIdOfDocId(String id)
   {
      JsArray<SourceDocument> docs = getSourceDocs();
      for (int i = 0; i < docs.length(); i++)
      {
         if (docs.get(i).getId() == id)
         {
            String windowId = docs.get(i).getSourceWindowId();
            if (windowId != null)
               return windowId;
            else
               return "";
         }
      }
      return null;
   }
   
   public void handleUnsavedChangesBeforeExit(
         ArrayList<UnsavedChangesTarget> targets,
         Command onCompleted)
   {
      // accumulate the unsaved change targets that represent satellite windows
      final JsArray<UnsavedChangesItem> items = JsArray.createArray().cast();
      for (UnsavedChangesTarget target: targets)
      {
         if (target instanceof UnsavedChangesItem)
         {
            items.push((UnsavedChangesItem)target);
         }
      }
      
      // let each window have a crack at saving the targets (the windows will
      // discard any targets they don't own)
      doForAllSourceWindows(new SourceWindowCommand() 
      {
         @Override
         public void execute(String windowId, WindowEx window,
               Command continuation)
         {
            handleUnsavedChangesBeforeExit(window, items, continuation);
         }
      },
      onCompleted);
   }
   
   public void fireEventToSourceWindow(String windowId, CrossWindowEvent<?> evt)
   {
      if (StringUtil.isNullOrEmpty(windowId) && !isMainSourceWindow())
      {
         pSatellite_.get().focusMainWindow();
         events_.fireEventToMainWindow(evt);
      }
      else
      {
         pSatelliteManager_.get().activateSatelliteWindow(
               SourceSatellite.NAME_PREFIX + windowId);
         WindowEx window = pSatelliteManager_.get().getSatelliteWindowObject(
               SourceSatellite.NAME_PREFIX + windowId);
         if (window != null)
         {
            events_.fireEventToSatellite(evt, window);
         }
         
      }
   }

   public void assignSourceDocWindowId(String docId, 
         String windowId, final Command onComplete)
   {
      // create the new property map
      HashMap<String,String> props = new HashMap<String,String>();
      props.put(SOURCE_WINDOW_ID, windowId);
      
      // update the doc window ID in place
      JsArray<SourceDocument> docs = getSourceDocs();
      for (int i = 0; i < docs.length(); i++)
      {
         SourceDocument doc = docs.get(i);
         if (doc.getId() == docId)
         {
            doc.assignSourceWindowId(windowId);
            break;
         }
      }
      
      // update the doc window ID on the server
      server_.modifyDocumentProperties(docId,
             props, new ServerRequestCallback<Void>()
            {
               @Override
               public void onResponseReceived(Void v)
               {
                  if (onComplete != null)
                     onComplete.execute();
               }

               @Override
               public void onError(ServerError error)
               {
                  display_.showErrorMessage("Can't Move Doc", 
                        "The document could not be " +
                        "moved to a different window: \n" + 
                        error.getMessage());
               }
            });
   }
   
   public void closeAllSatelliteDocs(final String caption, 
         final Command onCompleted)
   {
      doForAllSourceWindows(new OperationWithInput<Pair<String,WindowEx>>()
      {
         @Override
         public void execute(Pair<String, WindowEx> input)
         {
            // focus the satellite and ask it to close all its docs
            input.second.focus();
            closeAllDocs(input.second, caption, onCompleted);
         }
      });
      
      // return focus to the main window when done
      pSatellite_.get().focusMainWindow();
   }
   
   public ArrayList<UnsavedChangesTarget> getAllSatelliteUnsavedChanges()
   {
      final ArrayList<UnsavedChangesTarget> targets = 
            new ArrayList<UnsavedChangesTarget>();
      doForAllSourceWindows(new OperationWithInput<Pair<String,WindowEx>>()
      {
         @Override
         public void execute(Pair<String, WindowEx> input)
         {
            targets.addAll(JsArrayUtil.toArrayList(
                  getUnsavedChanges(input.second)));
         }
      });
      return targets;
   }
   
   // Event handlers ----------------------------------------------------------
   @Override
   public void onPopoutDoc(final PopoutDocEvent evt)
   {
      // assign a new window ID to the source document 
      final String windowId = createSourceWindowId();
      assignSourceDocWindowId(evt.getDocId(), windowId, 
            new Command()
            {
               @Override
               public void execute()
               {
                  openSourceWindow(windowId, evt.getPosition());
               }
            });
   }
   
   @Override
   public void onSourceDocAdded(SourceDocAddedEvent e)
   {
      // if the window that fired the event is not already the owner of the
      // document, make it the owner
      if (e.getDoc().getSourceWindowId() != e.getWindowId())
      {
         assignSourceDocWindowId(e.getDoc().getId(), 
               e.getWindowId(), null);
      }

      // ensure the doc isn't already in our index
      for (int i = 0; i < sourceDocs_.length(); i++)
      {
         if (sourceDocs_.get(i).getId() == e.getDoc().getId())
            return;
      }
      
      sourceDocs_.push(e.getDoc());
   }

   @Override
   public void onSatelliteClosed(SatelliteClosedEvent event)
   {
      // if this satellite is closing for quit/shutdown/close/etc., ignore it
      // (we only care about user-initiated window closure)
      if (windowsClosing_)
         return;
      
      // ignore closure when not a source window
      if (!event.getName().startsWith(SourceSatellite.NAME_PREFIX))
         return;
      
      // when the user closes a source window, close all the source docs it
      // contained
      for (int i = 0; i < sourceDocs_.length(); i++)
      {
         final SourceDocument doc = sourceDocs_.get(i);
         if (doc.getSourceWindowId() == sourceWindowId(event.getName()))
         {
            // change the window ID of the doc back to the main window
            assignSourceDocWindowId(doc.getId(), "", new Command()
            {
               @Override
               public void execute()
               {
                  // close the document when finished
                  server_.closeDocument(doc.getId(), 
                        new VoidServerRequestCallback());
               }
            });
         }
      }
   }

   @Override
   public void onAllSatellitesClosing(AllSatellitesClosingEvent event)
   {
      windowsClosing_ = true;
   }

   @Override
   public void onDocTabDragStarted(DocTabDragStartedEvent event)
   {
      // we are the main source window; fire the event to all the source 
      // satellites
      fireEventToAllSourceWindows(event);
   }

   @Override
   public void onDocWindowChanged(final DocWindowChangedEvent event)
   {
      if (event.getNewWindowId() == getSourceWindowId())
      {
         // if the doc is moving to this window, assign the ID before firing
         // events
         assignSourceDocWindowId(event.getDocId(), 
               event.getNewWindowId(), new Command()
               {
                  @Override
                  public void execute()
                  {
                     broadcastDocWindowChanged(event);
                  }
               });
      }
      else
      {
         broadcastDocWindowChanged(event);
      }
   }

   @Override
   public void onDocTabClosed(DocTabClosedEvent event)
   {
      JsArray<SourceDocument> sourceDocs = getSourceDocs();
      for (int i = 0; i < sourceDocs.length(); i++)
      {
         if (sourceDocs.get(i).getId() == event.getDocId())
         {
            JsArrayUtil.remove(sourceDocs, i);
            break;
         }
      }
   }

   @Override
   public void onShinyApplicationStatus(ShinyApplicationStatusEvent event)
   {
      fireEventToAllSourceWindows(event);
   }


   @Override
   public void onShowHelp(ShowHelpEvent event)
   {
      // when help requests come in from satellites, focus the main frame 
      if (!event.isFromMainWindow())
      {
         if (Desktop.isDesktop())
            Desktop.getFrame().bringMainFrameToFront();
         else
            WindowEx.get().focus();
      }
   }
   // Private methods ---------------------------------------------------------
   
   private void openSourceWindow(String windowId, Point position)
   {
      // create default options
      Size size = new Size(800, 800);
      Integer ordinal = null;

      // if we have geometry for the window, apply it
      SatelliteWindowGeometry geometry = windowGeometry_.getObject(windowId);
      if (geometry != null)
      {
         size = geometry.getSize();
         ordinal = geometry.getOrdinal();
         if (position == null)
            position = geometry.getPosition();
      }

      // assign ordinal if not already assigned
      if (ordinal == null)
         ordinal = ++maxOrdinal_;
      
      pSatelliteManager_.get().openSatellite(
            SourceSatellite.NAME_PREFIX + windowId, 
            SourceWindowParams.create(
                  ordinal,
                  pWorkbenchContext_.get().createWindowTitle()), 
            size, position);
      
      sourceWindows_.put(windowId, ordinal);
   }
   
   private void broadcastDocWindowChanged(DocWindowChangedEvent event)
   {
      if (isMainSourceWindow() && 
         event.getOldWindowId() != getSourceWindowId())
      {
         // this is the main window; pass the event on to the window that just
         // lost its doc
         fireEventToSourceWindow(event.getOldWindowId(), event);
      }
      else if (event.getNewWindowId() == getSourceWindowId())
      {
         // this is a satellite; pass the event on to the main window for
         // routing
         events_.fireEventToMainWindow(event);
      }
   }
   
   private String createSourceWindowId()
   {
      String alphanum = "0123456789abcdefghijklmnopqrstuvwxyz";
      String id = "w";
      for (int i = 0; i < 12; i++)
      {
         id += alphanum.charAt((int)(Math.random() * alphanum.length()));
      }
      return id;
   }
   
   private String sourceWindowId(String input)
   {
      if (input != null && input.startsWith(SourceSatellite.NAME_PREFIX))
      {
         return input.substring(SourceSatellite.NAME_PREFIX.length());
      }
      return "";
   }
   
   private final native JsArray<SourceDocument> getMainWindowSourceDocs() /*-{
      return $wnd.opener.rstudioSourceDocs;
   }-*/;
   
   private final native void exportFromMain() /*-{
      // the main window maintains an array of all open source documents 
      // across all satellites; rather than attempt to synchronize this list
      // among satellites, the main window exposes it on its window object
      // for the satellites to read 
      $wnd.rstudioSourceDocs = this.@org.rstudio.studio.client.workbench.views.source.SourceWindowManager::sourceDocs_;
   }-*/;

   private final native void closeAllDocs(WindowEx satellite, String caption, 
         Command onCompleted) /*-{
      satellite.rstudioCloseAllDocs(caption, onCompleted);
   }-*/;

   private final native JsArray<UnsavedChangesItem> getUnsavedChanges(
         WindowEx satellite) /*-{
      return satellite.rstudioGetUnsavedChanges();
   }-*/;
   
   private final native void handleUnsavedChangesBeforeExit(WindowEx satellite, 
         JsArray<UnsavedChangesItem> items, Command onCompleted) /*-{
      satellite.rstudioHandleUnsavedChangesBeforeExit(items, onCompleted);
   }-*/;
   
   private boolean updateWindowGeometry()
   {
      final ArrayList<String> changedWindows = new ArrayList<String>();
      final JsObject newGeometries = JsObject.createJsObject();

      doForAllSourceWindows(new OperationWithInput<Pair<String,WindowEx>>()
      {
         @Override
         public void execute(Pair<String, WindowEx> input)
         {
            String windowId = input.first;
            WindowEx window = input.second;

            // read the window's current geometry
            SatelliteWindowGeometry newGeometry = 
                  SatelliteWindowGeometry.create(
                        sourceWindows_.get(windowId),
                        window.getScreenX(), 
                        window.getScreenY(), 
                        window.getOuterWidth(), 
                        window.getOuterHeight());
            
            // compare to the old geometry (if any)
            if (windowGeometry_.hasKey(windowId))
            {
               SatelliteWindowGeometry oldGeometry = 
                     windowGeometry_.getObject(windowId);
               if (!oldGeometry.equals(newGeometry))
                  changedWindows.add(windowId);
            }
            else 
            {
               changedWindows.add(windowId);
            }
            newGeometries.setObject(windowId, newGeometry);
         };
      });
      
      if (changedWindows.size() > 0)
         windowGeometry_ = newGeometries;
      
      return changedWindows.size() > 0;
   }
   
   private void fireEventToAllSourceWindows(CrossWindowEvent<?> event)
   {
      for (String sourceWindowId: sourceWindows_.keySet())
      {
         fireEventToSourceWindow(sourceWindowId, event);
      }
   }
   
   // execute a command on all source windows (synchronously)
   private void doForAllSourceWindows(
         OperationWithInput<Pair<String,WindowEx>> command)
   {
      for (final String windowId: sourceWindows_.keySet())
      {
         final WindowEx window = pSatelliteManager_.get()
               .getSatelliteWindowObject(
                     SourceSatellite.NAME_PREFIX + windowId);
         if (window == null || window.isClosed())
            continue;
         
         command.execute(new Pair<String,WindowEx>(windowId, window));
      }
   }
   
   // execute a command on all source windows (async continuation-style)
   private void doForAllSourceWindows(final SourceWindowCommand command, 
         final Command completedCommand)
   {
      final SerializedCommandQueue queue = new SerializedCommandQueue();
      doForAllSourceWindows(new OperationWithInput<Pair<String,WindowEx>>()
      {
         @Override
         public void execute(final Pair<String, WindowEx> input)
         {
            queue.addCommand(new SerializedCommand()
            {
               @Override
               public void onExecute(Command continuation)
               {
                  command.execute(input.first, input.second, continuation);
               }
            });
         }
      });
      
      if (completedCommand != null)
      {
         queue.addCommand(new SerializedCommand() {
            public void onExecute(Command continuation)
            {
               completedCommand.execute();
               continuation.execute();
            }  
         });
      }
   }
   
   public interface SourceWindowCommand
   {
      public void execute(String windowId, WindowEx window, 
            Command continuation);
   }
   
   private final EventBus events_;
   private final Provider<SatelliteManager> pSatelliteManager_;
   private final Provider<Satellite> pSatellite_;
   private final Provider<WorkbenchContext> pWorkbenchContext_;
   private final SourceServerOperations server_;
   private final GlobalDisplay display_;

   private HashMap<String, Integer> sourceWindows_ = 
         new HashMap<String,Integer>();
   private JsArray<SourceDocument> sourceDocs_ = 
         JsArray.createArray().cast();
   private boolean windowsClosing_ = false;
   private JsObject windowGeometry_ = JsObject.createJsObject();
   private int maxOrdinal_ = 0;
   private int thisWindowOrdinal_ = 0;
   
   public final static String SOURCE_WINDOW_ID = "source_window_id";
}