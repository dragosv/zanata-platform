/*
 * Copyright 2012, Red Hat, Inc. and individual contributors as indicated by the
 * @author tags. See the copyright.txt file in the distribution for a full
 * listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this software; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA, or see the FSF
 * site: http://www.fsf.org.
 */

package org.zanata.webtrans.client.service;

import java.util.List;

import org.hamcrest.Matchers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.zanata.common.ContentState;
import org.zanata.common.LocaleId;
import org.zanata.model.HLocale;
import org.zanata.model.HTextFlow;
import org.zanata.model.TestFixture;
import org.zanata.webtrans.client.presenter.TargetContentsPresenter;
import org.zanata.webtrans.client.events.LoadingEvent;
import org.zanata.webtrans.client.events.PageCountChangeEvent;
import org.zanata.webtrans.client.events.TableRowSelectedEvent;
import org.zanata.webtrans.client.presenter.TransUnitsTablePresenter;
import org.zanata.webtrans.client.presenter.UserConfigHolder;
import org.zanata.webtrans.client.resources.TableEditorMessages;
import org.zanata.webtrans.client.rpc.CachingDispatchAsync;
import org.zanata.webtrans.server.rpc.GetTransUnitListHandler;
import org.zanata.webtrans.server.rpc.GetTransUnitsNavigationHandler;
import org.zanata.webtrans.shared.model.DocumentId;
import org.zanata.webtrans.shared.model.ProjectIterationId;
import org.zanata.webtrans.shared.model.TransUnit;
import org.zanata.webtrans.shared.model.TransUnitId;
import org.zanata.webtrans.shared.model.WorkspaceId;
import org.zanata.webtrans.shared.rpc.AbstractWorkspaceAction;
import org.zanata.webtrans.shared.rpc.GetTransUnitList;
import org.zanata.webtrans.shared.rpc.GetTransUnitListResult;
import org.zanata.webtrans.shared.rpc.GetTransUnitsNavigation;
import org.zanata.webtrans.shared.rpc.GetTransUnitsNavigationResult;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gwt.event.shared.GwtEvent;
import com.google.gwt.user.client.rpc.AsyncCallback;

import net.customware.gwt.dispatch.server.ActionHandler;
import net.customware.gwt.dispatch.shared.Action;
import net.customware.gwt.dispatch.shared.ActionException;
import net.customware.gwt.dispatch.shared.Result;
import net.customware.gwt.presenter.client.EventBus;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@Test(groups = { "unit-tests" }, description = "This test uses SeamAutowire with mockito to simulate a RPC call environment")
public class NavigationServiceIntegrationTest
{
   private static final Logger log = LoggerFactory.getLogger(NavigationServiceIntegrationTest.class);
   private HLocale hLocale = new HLocale(new LocaleId("en"));
   private WorkspaceId workspaceId = new WorkspaceId(new ProjectIterationId("project", "master"), hLocale.getLocaleId());

   // @formatter:off
   private final List<HTextFlow> hTextFlows = Lists.newArrayList(
         TestFixture.makeHTextFlow(0, hLocale, ContentState.New),
         TestFixture.makeHTextFlow(1, hLocale, ContentState.New),
         TestFixture.makeHTextFlow(2, hLocale, ContentState.NeedReview),
         TestFixture.makeHTextFlow(3, hLocale, ContentState.Approved),
         TestFixture.makeHTextFlow(4, hLocale, ContentState.NeedReview),
         TestFixture.makeHTextFlow(5, hLocale, ContentState.New)
   );

   private NavigationService service;
   @Mock
   private CachingDispatchAsync dispatcher;
   @Mock
   private EventBus eventBus;

   private ModalNavigationStateHolder navigationStateHolder;

   private DocumentId documentId = new DocumentId(1L);

   @Captor
   private ArgumentCaptor<AbstractWorkspaceAction> actionCaptor;

   @Captor
   private ArgumentCaptor<AsyncCallback> asyncCallbackCaptor;

   //captured dispatcher arguments
   private GetTransUnitList getTransUnitList;
   private GetTransUnitsNavigation getTransUnitsNavigation;
   private AsyncCallback<GetTransUnitListResult> getTransUnitListCallback;
   private AsyncCallback<GetTransUnitsNavigationResult> getTransUnitsNavigationCallback;
   
   private GetTransUnitListHandler getTransUnitListHandler;
   private GetTransUnitsNavigationHandler getTransUnitsNavigationHandler;

   private GetTransUnitActionContext context;
   @Mock
   private TableEditorMessages messages;
   @Mock
   private TransUnitsTablePresenter transUnitsTablePresenter;
   @Mock
   private TargetContentsPresenter targetContentsPresenter;

   @BeforeMethod
   public void setUp() throws Exception
   {
      MockitoAnnotations.initMocks(this);
      UserConfigHolder configHolder = new UserConfigHolder();

      MockHandlerFactory handlerFactory = new MockHandlerFactory();
      getTransUnitListHandler = handlerFactory.createGetTransUnitListHandlerWithBehavior(documentId, hTextFlows, hLocale);
      getTransUnitsNavigationHandler = handlerFactory.createGetTransUnitsNavigationHandlerWithBehavior(documentId, hTextFlows, hLocale);

      service = new NavigationService(eventBus, dispatcher, configHolder, messages);
      service.addPageDataChangeListener(transUnitsTablePresenter);
      navigationStateHolder = service.getNavigationStateHolder();

      context = new GetTransUnitActionContext(documentId);
   }

   @SuppressWarnings("unchecked")
   private void verifyDispatcherAndCaptureArguments()
   {
      verify(dispatcher, atLeastOnce()).execute(actionCaptor.capture(), asyncCallbackCaptor.capture());

      List<AbstractWorkspaceAction> actions = actionCaptor.getAllValues();
      List<AsyncCallback> callbacks = asyncCallbackCaptor.getAllValues();

      for (int i = 0, allValuesSize = actions.size(); i < allValuesSize; i++)
      {
         if (actions.get(i) instanceof GetTransUnitList)
         {
            getTransUnitList = (GetTransUnitList) actions.get(i);
            getTransUnitList.setWorkspaceId(workspaceId);
            getTransUnitListCallback = (AsyncCallback<GetTransUnitListResult>) callbacks.get(i);
         }
         else
         {
            getTransUnitsNavigation = (GetTransUnitsNavigation) actions.get(i);
            getTransUnitsNavigation.setWorkspaceId(workspaceId);
            getTransUnitsNavigationCallback = (AsyncCallback<GetTransUnitsNavigationResult>) callbacks.get(i);
         }
      }
   }

   //look at this AWESOME generic work ;)
   private static <A extends Action<R>, R extends Result, H extends ActionHandler<A, R>> R callHandler(H handler, A action)
   {
      R result;
      try
      {
         result = handler.execute(action, null);
      }
      catch (ActionException e)
      {
         throw new RuntimeException("fail to call getTransUnitListHandler.execute()", e);
      }

      log.info("result: {}", result);
      return result;
   }

   private void simulateRPCCallbackOnSuccess()
   {
      verifyDispatcherAndCaptureArguments();
      getTransUnitListCallback.onSuccess(callHandler(getTransUnitListHandler, getTransUnitList));
      getTransUnitsNavigationCallback.onSuccess(callHandler(getTransUnitsNavigationHandler, getTransUnitsNavigation));
   }

   @Test
   public void canMockHandler()
   {
      service.init(context.changeCount(6));
      verifyDispatcherAndCaptureArguments();

      GetTransUnitListResult getTransUnitListResult = callHandler(getTransUnitListHandler, getTransUnitList);
      assertThat(getTransUnitListResult.getDocumentId(), equalTo(documentId));
      assertThat(asIds(getTransUnitListResult.getUnits()), contains(0, 1, 2, 3, 4, 5));

      GetTransUnitsNavigationResult navigationResult = callHandler(getTransUnitsNavigationHandler, getTransUnitsNavigation);
      assertThat(navigationResult.getDocumentId(), equalTo(documentId));
      assertThat(navigationResult.getIdIndexList(), contains(0L, 1L, 2L, 3L, 4L, 5L));
      assertThat(navigationResult.getTransIdStateList(), hasEntry(0L, ContentState.New));
      assertThat(navigationResult.getTransIdStateList(), hasEntry(1L, ContentState.New));
      assertThat(navigationResult.getTransIdStateList(), hasEntry(2L, ContentState.NeedReview));
      assertThat(navigationResult.getTransIdStateList(), hasEntry(3L, ContentState.Approved));
      assertThat(navigationResult.getTransIdStateList(), hasEntry(4L, ContentState.NeedReview));
      assertThat(navigationResult.getTransIdStateList(), hasEntry(5L, ContentState.New));
   }

   @Test
   public void canGoToFirstPage()
   {
      service.init(context.changeCount(3));
      simulateRPCCallbackOnSuccess();
      service.gotoPage(0);
      simulateRPCCallbackOnSuccess();
      assertThat(getPageDataModelAsIds(), contains(0, 1, 2));
      assertThat(navigationStateHolder.getCurrentPage(), is(0));

      //go again won't cause another call to server
      service.gotoPage(0);
      verifyNoMoreInteractions(dispatcher);
      assertThat(getPageDataModelAsIds(), contains(0, 1, 2));
      assertThat(navigationStateHolder.getCurrentPage(), is(0));
   }

   private List<Integer> getPageDataModelAsIds()
   {
      return asIds(service.getCurrentPageValues());
   }

   @Test
   public void canGoToLastPageWithNotPerfectDivide()
   {
      service.init(context.changeCount(4));
      simulateRPCCallbackOnSuccess();
      service.gotoPage(1);
      simulateRPCCallbackOnSuccess();
      assertThat(getPageDataModelAsIds(), contains(4, 5));
      assertThat(navigationStateHolder.getCurrentPage(), is(1));

      service.gotoPage(1);
      verifyNoMoreInteractions(dispatcher);
   }

   @Test
   public void canGoToLastPageWithPerfectDivide() {
      service.init(context.changeCount(3));
      simulateRPCCallbackOnSuccess();
      service.gotoPage(1);
      simulateRPCCallbackOnSuccess();
      assertThat(getPageDataModelAsIds(), contains(3, 4, 5));
      assertThat(navigationStateHolder.getCurrentPage(), is(1));

      service.gotoPage(1);
      verifyNoMoreInteractions(dispatcher);
      assertThat(getPageDataModelAsIds(), contains(3, 4, 5));
      assertThat(navigationStateHolder.getCurrentPage(), is(1));
   }

   @Test
   public void canHavePageCountGreaterThanActualSize() {
      service.init(context.changeCount(10));
      simulateRPCCallbackOnSuccess();
      service.gotoPage(100);
      simulateRPCCallbackOnSuccess();
      assertThat(getPageDataModelAsIds(), contains(0, 1, 2, 3, 4, 5));
      assertThat(navigationStateHolder.getCurrentPage(), is(0));

      service.gotoPage(0);
      simulateRPCCallbackOnSuccess();
      assertThat(getPageDataModelAsIds(), contains(0, 1, 2, 3, 4, 5));
      assertThat(navigationStateHolder.getCurrentPage(), is(0));
   }

   @Test
   public void canGoToNextPage()
   {
      service.init(context.changeCount(2));
      simulateRPCCallbackOnSuccess();
      service.gotoPage(1);
      simulateRPCCallbackOnSuccess();
      assertThat(getPageDataModelAsIds(), contains(2, 3));
      assertThat(navigationStateHolder.getCurrentPage(), is(1));

      service.gotoPage(2);
      simulateRPCCallbackOnSuccess();
      assertThat(getPageDataModelAsIds(), contains(4, 5));
      assertThat(navigationStateHolder.getCurrentPage(), is(2));

      //can't go any further
      service.gotoPage(2);
      verifyNoMoreInteractions(dispatcher);
      assertThat(getPageDataModelAsIds(), contains(4, 5));
      assertThat(navigationStateHolder.getCurrentPage(), is(2));
   }

   @Test
   public void canGoToPreviousPage()
   {
      service.init(context.changeCount(2));
      simulateRPCCallbackOnSuccess();
      //should be on first page already
      service.gotoPage(0);
      verifyNoMoreInteractions(dispatcher);
      assertThat(getPageDataModelAsIds(), contains(0, 1));
      assertThat(navigationStateHolder.getCurrentPage(), is(0));

      service.gotoPage(2);
      simulateRPCCallbackOnSuccess();
      assertThat(getPageDataModelAsIds(), contains(4, 5));
      assertThat(navigationStateHolder.getCurrentPage(), is(2));

      service.gotoPage(1);
      simulateRPCCallbackOnSuccess();
      assertThat(getPageDataModelAsIds(), contains(2, 3));
      assertThat(navigationStateHolder.getCurrentPage(), is(1));

      service.gotoPage(0);
      simulateRPCCallbackOnSuccess();
      assertThat(getPageDataModelAsIds(), contains(0, 1));
      assertThat(navigationStateHolder.getCurrentPage(), is(0));

      //can't go any further
      service.gotoPage(0);
      verifyNoMoreInteractions(dispatcher);
      assertThat(navigationStateHolder.getCurrentPage(), is(0));
   }

   @Test
   public void canGoToPage()
   {
      service.init(context.changeCount(3));
      simulateRPCCallbackOnSuccess();
      service.gotoPage(1);
      simulateRPCCallbackOnSuccess();
      assertThat(getPageDataModelAsIds(), contains(3, 4, 5));
      assertThat(navigationStateHolder.getCurrentPage(), is(1));

      //page out of bound
      service.gotoPage(7);
      simulateRPCCallbackOnSuccess();
      assertThat(getPageDataModelAsIds(), contains(3, 4, 5));
      assertThat(navigationStateHolder.getCurrentPage(), is(1));

      //page is negative
      service.gotoPage(-1);
      simulateRPCCallbackOnSuccess();
      assertThat(getPageDataModelAsIds(), contains(0, 1, 2));
      assertThat(navigationStateHolder.getCurrentPage(), is(0));
   }

   @Test
   public void onRPCSuccess()
   {
      service.init(context.changeCount(3));
      verify(eventBus, times(2)).fireEvent(LoadingEvent.START_EVENT);
      simulateRPCCallbackOnSuccess();
      verify(eventBus).fireEvent(LoadingEvent.FINISH_EVENT);

      // behaviour on GetTransUnitList success
      verify(transUnitsTablePresenter).showDataForCurrentPage(service.getCurrentPageValues());
      ArgumentCaptor<GwtEvent> eventCaptor = ArgumentCaptor.forClass(GwtEvent.class);
      verify(eventBus, atLeastOnce()).fireEvent(eventCaptor.capture());

      TableRowSelectedEvent tableRowSelectedEvent = extractFromEvents(eventCaptor.getAllValues(), TableRowSelectedEvent.class);
      TransUnitId firstItem = new TransUnitId(hTextFlows.get(0).getId());
      assertThat(tableRowSelectedEvent.getSelectedId(), Matchers.equalTo(firstItem));

      // behaviour on GetTransUnitNavigation success
      PageCountChangeEvent pageCountChangeEvent = extractFromEvents(eventCaptor.getAllValues(), PageCountChangeEvent.class);
      assertThat(pageCountChangeEvent.getPageCount(), Matchers.is(2));
   }

   private <E extends GwtEvent<?>> E extractFromEvents(List<GwtEvent> events, final Class<E> eventType)
   {
      GwtEvent gwtEvent = Iterables.find(events, new Predicate<GwtEvent>()
      {
         @Override
         public boolean apply(GwtEvent input)
         {
            return eventType.isAssignableFrom(input.getClass());
         }
      });
      return (E) gwtEvent;
   }

   private static List<Integer> asIds(List<TransUnit> transUnits)
   {
      return Lists.newArrayList(Collections2.transform(transUnits, new Function<TransUnit, Integer>()
      {
         @Override
         public Integer apply(TransUnit from)
         {
            return (int) from.getId().getId();
         }
      }));
   }
}
