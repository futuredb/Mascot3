package com.generativemascot.app.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetHostView
import android.content.Intent
import android.graphics.Bitmap
import android.os.Parcel
import android.widget.AdapterViewFlipper
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.ViewFlipper
import com.generativemascot.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35, 36], application = Application::class)
class WidgetViewsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun open() = PendingIntent.getActivity(context, 1, Intent("widget-test"), PendingIntent.FLAG_IMMUTABLE)
    private fun inflateCollection(views: RemoteViews): AdapterViewFlipper {
        // Fixed collection actions require a real widget host as their root parent.
        val host = AppWidgetHostView(context)
        val root = views.apply(context, host) as AdapterViewFlipper
        host.addView(root)
        return root
    }
    private fun rows(count: Int): List<RemoteViews> = List(count) {
        val side = widgetBitmapSide(count)
        RemoteViews(context.packageName, R.layout.mascot_widget_frame).apply {
            setImageViewBitmap(R.id.widget_frame_image, Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888))
        }
    }

    @Test fun samsungFlipperUsesTheExactClipIntervalAndOnlyOneVisibleFrame() {
        for (layout in listOf(R.layout.mascot_widget, R.layout.mascot_widget_alt)) {
            val root = preloadedWidgetViews(context.packageName, layout, rows(12), 217, open())
                .apply(context, FrameLayout(context)) as ViewFlipper
            assertEquals(12, root.childCount)
            assertEquals(217, root.flipInterval)
            root.showNext()
            assertEquals(1, (0 until root.childCount).count { root.getChildAt(it).visibility == android.view.View.VISIBLE })
            assertNotNull(root.currentView.findViewById<ImageView>(R.id.widget_frame_image).drawable)
        }
    }

    @Test fun pixelFixedCollectionDoesNotNeedARemoteViewsServiceForFrames() {
        for (layout in listOf(R.layout.mascot_widget_adapter, R.layout.mascot_widget_adapter_alt)) {
            val root = inflateCollection(fixedCollectionWidgetViews(context.packageName, layout, rows(72), 83, open()))
            assertEquals(72, root.adapter.count)
            assertEquals(83, root.flipInterval)
            assertEquals(0L, root.inAnimation.duration)
            assertEquals(0L, root.outAnimation.duration)
            assertNotNull(root.currentView)
            root.showNext()
            assertEquals(1, root.displayedChild)
        }
    }

    @Test fun maximumFixedCollectionSurvivesParcelingAndInflating() {
        val views = fixedCollectionWidgetViews(context.packageName, R.layout.mascot_widget_adapter, rows(96), 83, open())
        val parcel = Parcel.obtain()
        try {
            views.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = RemoteViews.CREATOR.createFromParcel(parcel)
            val root = inflateCollection(restored)
            assertEquals(96, root.adapter.count)
            assertEquals(83, root.flipInterval)
            // Robolectric's bitmap parcel representation is not native Binder's shared-memory
            // representation. Actual decoded memory is bounded separately by WidgetRenderPolicy.
        } finally { parcel.recycle() }
    }

    @Test fun allXmlTimingBucketsAndAlternateLayoutsInflateWithoutUnsupportedMethods() {
        for (interval in com.generativemascot.app.data.WIDGET_SUPPORTED_INTERVALS) for (alternate in listOf(false, true)) {
            val layout = widgetAdapterLayout(interval, alternate)
            val root = inflateCollection(fixedCollectionWidgetViews(context.packageName, layout, rows(12), interval, open()))
            assertEquals(interval, root.flipInterval)
            assertEquals(12, root.adapter.count)
        }
    }
}
