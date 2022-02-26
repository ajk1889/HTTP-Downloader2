package resonance.http.httpdownloader.helpers

import android.content.Context
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.Transformation
import android.widget.ImageView
import resonance.http.httpdownloader.R

class AnimFactory(
    private var duration: Long = 100L
) {
    fun listRemoveAnim(view: View, heightExpanded: Int, onEnd: () -> Unit): Animation {
        val anim2 = object : Animation() {
            override fun applyTransformation(interpolatedTime: Float, t: Transformation?) {
                val height = heightExpanded * (1 - interpolatedTime)
                view.translationX = interpolatedTime * view.width
                view.layoutParams = view.layoutParams.apply { this.height = height.toInt() }
                view.requestLayout()
            }
        }; anim2.duration = duration
        anim2.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationRepeat(animation: Animation?) {}
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                view.clearAnimation()
                view.translationX = 0f
                onEnd()
            }
        }); return anim2
    }

    fun toggleAnim(view: View, from: Int, to: Int, onEnd: (() -> Unit)? = null): Animation {
        val anim = object : Animation() {
            override fun applyTransformation(interpolatedTime: Float, t: Transformation?) {
                val height = (to - from) * interpolatedTime + from
                view.layoutParams = view.layoutParams.apply { this.height = height.toInt() }
                view.requestLayout()
            }
        }.apply {
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    onEnd?.invoke()
                }
            })
        }
        anim.duration = duration
        return anim
    }

    fun animateBtn(view: ImageView, newRes: Int, onEnd: (() -> Unit)? = null) {
        val anim = AnimationUtils.loadAnimation(view.context, R.anim.compress)
        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationRepeat(animation: Animation?) {}
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                if (newRes == R.drawable.blank) view.hide()
                else {
                    view.unHide()
                    view.tag = newRes
                    view.setImageResource(newRes)
                }

                with(AnimationUtils.loadAnimation(view.context, R.anim.expand)) {
                    setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationRepeat(animation: Animation?) {}
                        override fun onAnimationStart(animation: Animation?) {}
                        override fun onAnimationEnd(animation: Animation?) {
                            view.isEnabled = true; onEnd?.invoke()
                        }
                    })
                    view.startAnimation(this)
                }
            }
        })
        view.startAnimation(anim)
    }

    fun revealBtn(view: ImageView, newRes: Int, onEnd: (() -> Unit)? = null) {
        if (newRes == R.drawable.blank) {
            view.hide()
        } else {
            view.unHide()
            view.tag = newRes
            view.setImageResource(newRes)
        }

        val anim = AnimationUtils.loadAnimation(view.context, R.anim.expand)
        anim.duration = 150
        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationRepeat(animation: Animation?) {}
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                view.isEnabled = true; onEnd?.invoke()
            }
        })

        view.isEnabled = false
        view.startAnimation(anim)
    }

    //hyt & wdt are final height & width
    fun menuExpandAnim(view: View, hyt: Int, wdt: Int): Animation {
        return object : Animation() {
            override fun applyTransformation(interpolatedTime: Float, t: Transformation?) {
                view.layoutParams = view.layoutParams.apply {
                    this.height = (hyt * interpolatedTime).toInt()
                    this.width = (wdt * interpolatedTime).toInt()
                }
                view.requestLayout()
            }
        }.apply {
            duration = this@AnimFactory.duration
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationStart(animation: Animation?) {
                    view.unHide()
                }

                override fun onAnimationEnd(animation: Animation?) {
                    view.layoutParams = view.layoutParams.apply { height = hyt; width = wdt }
                }
            })
        }
    }

    //hyt & wdt are initial height & width
    fun menuContractAnim(view: View, hyt: Int, wdt: Int, onEnd: () -> Unit): Animation {
        return object : Animation() {
            override fun applyTransformation(interpolatedTime: Float, t: Transformation?) {
                view.layoutParams = view.layoutParams.apply {
                    this.height = hyt - (hyt * interpolatedTime).toInt()
                    this.width = wdt - (wdt * interpolatedTime).toInt()
                }
                view.requestLayout()
            }
        }.apply {
            duration = this@AnimFactory.duration
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    view.hide()
                    view.layoutParams = view.layoutParams.apply { height = hyt; width = wdt }
                    onEnd()
                }
            })
        }
    }

    fun animateTabReplacement(context: Context, old: View?, new: View) {
        new.setGone()
        val blinkOn = AnimationUtils.loadAnimation(context, R.anim.blink_on)
        val blinkOff = AnimationUtils.loadAnimation(context, R.anim.blink_off)
        blinkOff.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationRepeat(animation: Animation?) {}
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                old?.setGone()
                new.unHide()
                new.startAnimation(blinkOn)
            }
        })
        old?.startAnimation(blinkOff) ?: {
            new.unHide(); new.startAnimation(blinkOn)
        }.invoke()
    }

    fun rotateIcon(view: ImageView, from: Int, to: Int, onEnd: (() -> Unit)? = null): Animation {
        return object : Animation() {
            override fun applyTransformation(interpolatedTime: Float, t: Transformation?) {
                view.rotation = from + (to - from) * interpolatedTime
                view.requestLayout()
            }
        }.apply {
            duration = this@AnimFactory.duration
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    onEnd?.invoke()
                }
            })
        }
    }
}