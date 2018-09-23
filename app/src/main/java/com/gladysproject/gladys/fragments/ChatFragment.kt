package com.gladysproject.gladys.fragments

import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import android.support.design.widget.CoordinatorLayout
import android.support.design.widget.Snackbar
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat.getColor
import android.support.v7.content.res.AppCompatResources
import android.support.v7.widget.LinearLayoutManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.view.View.OnTouchListener
import android.widget.TextView
import com.gladysproject.gladys.R
import com.gladysproject.gladys.adapters.MessageAdapter
import com.gladysproject.gladys.database.GladysDb
import com.gladysproject.gladys.database.entity.Message
import com.gladysproject.gladys.utils.ConnectivityAPI
import com.gladysproject.gladys.utils.GladysAPI
import com.gladysproject.gladys.utils.SelfSigningClientBuilder
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.fragment_chat.*
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.lang.Exception

class ChatFragment : Fragment() {

    private var token : String = ""
    private lateinit var retrofit : Retrofit
    private lateinit var socket : Socket
    private var messages = mutableListOf<Message>()
    private lateinit var adapter : MessageAdapter

    /** Initialize new message variable */
    private var newMessage : Message = object : Message("", 0, "", "", 0){}

    companion object {
        fun newInstance() = ChatFragment()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        setHasOptionsMenu(true)
        activity?.loadingCircle?.visibility = View.VISIBLE
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onStart() {
        super.onStart()

        retrofit = Retrofit.Builder()
                .baseUrl(ConnectivityAPI.getUrl(context!!)) /** The function getUrl return string address */
                .addConverterFactory(MoshiConverterFactory.create())
                .client(SelfSigningClientBuilder.unsafeOkHttpClient)
                .build()

        token = PreferenceManager.getDefaultSharedPreferences(context).getString("token", "dgdfg")!!

        getMessages()

        /** Set drawable manually because API 21 and lower not support the vector drawable */
        message.setCompoundDrawablesWithIntrinsicBounds(null, null, AppCompatResources.getDrawable(context!!, R.drawable.ic_send_24dp), null)

        message.setOnTouchListener(OnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                if (event.rawX >= message.right - message.compoundDrawables[2].bounds.width()) {
                    if (message.text.isNotEmpty() || message.text != null) sendMessage(message.text.toString())
                    return@OnTouchListener false
                }
            }
            false
        })

        message.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                if (p3 == 0 && p1 == 0) {setTextViewDrawableColor(message, R.color.secondaryDarkColor)}
                else if (p3 == 1) {setTextViewDrawableColor(message, R.color.primaryColor)}
            }
            override fun afterTextChanged(p0: Editable?) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        })

        /** Scrolling to bottom when the keyboard is opened */
        chat_rv.addOnLayoutChangeListener{_: View, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int ->
            if(::adapter.isInitialized) chat_rv.scrollToPosition(adapter.itemCount - 1)
        }

        socket = ConnectivityAPI.Companion.WebSocket.getInstance(context!!)!!
        socket.on("message", onNewMessage)
    }

    private fun getMessages(){
        retrofit
                .create(GladysAPI::class.java)
                .getMessages(token)
                .enqueue(object : Callback<MutableList<Message>> {

                    override fun onResponse(call: Call<MutableList<Message>>, response: Response<MutableList<Message>>) {
                        if(response.code() == 200) {

                            messages = response.body()!!
                            if(messages.isNotEmpty()) refreshView(messages)
                            else showEmptyView()

                            /** Insert messages in database */
                            launch {
                                GladysDb.database?.messageDao()?.deleteMessages()
                                GladysDb.database?.messageDao()?.insertMessages(messages)
                            }
                        } else {
                            showSnackBar()
                        }
                    }

                    override fun onFailure(call: Call<MutableList<Message>>, err: Throwable) = runBlocking {

                        launch {
                            messages = GladysDb.database?.messageDao()?.getAllMessages()!!
                        }.join()

                        if(messages.isNotEmpty()) refreshView(messages)
                        else showEmptyView()

                        showSnackBar()
                    }
                })
    }

    private fun sendMessage(text : String){
        retrofit
                .create(GladysAPI::class.java)
                .sendMessage(text,null ,token)
                .enqueue(object : Callback<Message> {
                    override fun onResponse(call: Call<Message>, response: Response<Message>) {
                        if(response.code() == 200) {
                            /** Reset fields */
                            message.setText("")

                            /** Insert new message in UI */
                            messages.add(messages.size ,response.body()!!)
                            adapter.notifyItemInserted(messages.size)
                            chat_rv.scrollToPosition(adapter.itemCount - 1)

                            if(messages.size == 1){
                                refreshView(messages)
                                chat_rv.visibility = View.VISIBLE
                                empty_state_message_chat.visibility = View.INVISIBLE
                            }

                            /** Insert new message in database */
                            launch {
                                GladysDb.database?.messageDao()?.insertMessage(response.body()!!)
                            }
                        } else {
                            showSnackBar()
                        }
                    }
                    override fun onFailure(call: Call<Message>, err: Throwable) {
                        showSnackBar()
                    }
                })
    }

    private val onNewMessage = Emitter.Listener { args ->

        val data = args[0] as JSONObject
        newMessage.text = data.getString("text")
        newMessage.sender = null /** The null sender is Gladys */

        /** Insert new message in database */
        launch {
            GladysDb.database?.messageDao()?.insertMessage(newMessage)
        }

        /** Insert new message in UI */
        activity!!.runOnUiThread {
            messages.add(messages.size, newMessage)
            adapter.notifyItemInserted(messages.size)
            chat_rv.scrollToPosition(adapter.itemCount - 1)

            if(messages.size == 1){
                refreshView(messages)
                chat_rv.visibility = View.VISIBLE
                empty_state_message_chat.visibility = View.INVISIBLE
            }
        }
    }

    fun refreshView(data : List<Message>){
        if(chat_rv != null){
            chat_rv.layoutManager = LinearLayoutManager(context)
            adapter = MessageAdapter(data)
            chat_rv.adapter = adapter
            chat_rv.scrollToPosition(adapter.itemCount -1)

            activity?.loadingCircle?.visibility = View.INVISIBLE
        }
    }

    fun showEmptyView(){
        if(chat_rv != null) {
            chat_rv.visibility = View.INVISIBLE
            activity?.loadingCircle?.visibility = View.INVISIBLE
            empty_state_message_chat.visibility = View.VISIBLE
        }
    }

    private fun setTextViewDrawableColor(textView: TextView, color: Int) {
        for (drawable in textView.compoundDrawables) {
            if (drawable != null){
                drawable.clearColorFilter()
                drawable.colorFilter = PorterDuffColorFilter(getColor(context!!, color), PorterDuff.Mode.SRC_IN)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.clear()
        inflater.inflate(R.menu.toolbar_menu, menu)
        //menu.findItem(R.id.minimize_button).isVisible = true
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        val id = item!!.itemId
        if (id == R.id.minimize_button) {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)){
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + "com.gladysproject.gladys"))
                startActivityForResult(intent, 2084)
            } else {
                startChatHeadService()
            }

            return true
        }

        return super.onOptionsItemSelected(item)
    }


    override fun onPause() {
        super.onPause()

        /** Remove listener on message event when the fragment is paused */
        socket.off("message", onNewMessage)
    }

    override fun onResume() {
        super.onResume()
        if(activity?.bottom_navigation?.selectedItemId != R.id.chat)activity?.bottom_navigation?.selectedItemId = R.id.chat
    }

    private fun startChatHeadService(){
        //val intent = Intent(context, ChatHeadService::class.java)
        //if (context != null) {
        //    context!!.startService(intent)
        //}
    }

    fun showSnackBar(){
        try {
            if (ConnectivityAPI.getUrl(this@ChatFragment.context!!) == "http://noconnection") {
                Snackbar.make(chat_cl, R.string.no_connection, Snackbar.LENGTH_LONG)
                        .apply {
                            view.layoutParams = (view.layoutParams as CoordinatorLayout.LayoutParams)
                                    .apply { setMargins(leftMargin, topMargin, rightMargin, activity?.bottom_navigation?.height!! + 22) }
                        }.show()
            } else {
                Snackbar.make(chat_cl, R.string.error, Snackbar.LENGTH_LONG)
                        .apply {
                            view.layoutParams = (view.layoutParams as CoordinatorLayout.LayoutParams)
                                    .apply { setMargins(leftMargin, topMargin, rightMargin, activity?.bottom_navigation?.height!! + 22) }
                        }.show()
            }
        } catch (er: Exception){}
    }
}
