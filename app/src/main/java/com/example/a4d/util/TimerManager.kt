package com.example.a4d.util

import android.os.CountDownTimer
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object TimerManager {
    private var countDownTimer: CountDownTimer? = null
    
    private val _remainingTime = MutableLiveData<Long>(0)
    val remainingTime: LiveData<Long> = _remainingTime

    private val _isTimerRunning = MutableLiveData<Boolean>(false)
    val isTimerRunning: LiveData<Boolean> = _isTimerRunning

    fun startTimer(minutes: Int) {
        countDownTimer?.cancel()
        val millis = minutes * 60 * 1000L
        
        countDownTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                _remainingTime.postValue(millisUntilFinished)
            }

            override fun onFinish() {
                _remainingTime.postValue(0)
                _isTimerRunning.postValue(false)
            }
        }.start()
        
        _isTimerRunning.postValue(true)
    }

    fun stopTimer() {
        countDownTimer?.cancel()
        _remainingTime.postValue(0)
        _isTimerRunning.postValue(false)
    }
}