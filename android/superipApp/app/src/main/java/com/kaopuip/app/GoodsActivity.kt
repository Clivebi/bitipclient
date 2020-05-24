package com.kaopuip.app

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kaopuip.app.common.*
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.android.synthetic.main.activity_goods.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.startActivity
import org.jetbrains.anko.uiThread
import java.lang.Exception


class GoodsActivity : AppCompatActivity(){
    private val GOODS_URL = "http://8.129.164.30:6710/goodlist.do"
    class GoodsItem(val Name:String,val Price:String,val Discount:String,val Star:Boolean)
    private val mDataSource:ArrayList<GoodsItem> = arrayListOf()
    private val mAdapter: GoodsListAdapter = GoodsListAdapter()
    inner class GoodsListAdapter: RecyclerView.Adapter<GoodsListAdapter.ViewHolder>(){
        override fun getItemCount(): Int {
            return mDataSource.size
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(this@GoodsActivity).inflate(R.layout.item_goods,parent,false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val data = mDataSource[position]
            holder.goodsName.text = data.Name
            holder.goodsPrice.text = data.Price
            holder.goodsDiscount.text = data.Discount
            if (data.Star){
                holder.star.visibility = View.VISIBLE
            }else{
                holder.star.visibility = View.INVISIBLE
            }
            holder.container.setOnClickListener {
                CheckoutSheet(this@GoodsActivity, data).setUpdateCallback {
                    if(it != null){
                        devicecount.text = getString(R.string.format_device_count,it.ipsize)
                        expire.text = getString(R.string.format_expire,it.expire)
                    }
                }.dialog().show()
            }
        }

        inner class ViewHolder(view: View): RecyclerView.ViewHolder(view){
            var container: ConstraintLayout =view.findViewById(R.id.container)
            var star:ImageView = view.findViewById(R.id.star)
            var goodsName: TextView =view.findViewById(R.id.goodsname)
            var goodsPrice: TextView =view.findViewById(R.id.goodsprice)
            var goodsDiscount: TextView =view.findViewById(R.id.goodsdiscount)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        translucentActionBar()
        setContentView(R.layout.activity_goods)
        toolbar.layoutParams.height += statusBarHeight()
        toolbar.requestLayout()
        moreview.setOnClickListener {
        }
        name.text = app().api.getLoginInfo()!!.user
        val info = app().api.getUserInfo()
        if(info != null){
            devicecount.text = getString(R.string.format_device_count,info.ipsize)
            expire.text = getString(R.string.format_expire,info.expire)
        }
        recyclerview.layoutManager = LinearLayoutManager(this)
        recyclerview.adapter = mAdapter
        val div = DividerItemDecorationWithOffset(
            dp2px(16), this, DividerItemDecoration.VERTICAL
        )
        div.setDrawable(getDrawable(R.drawable.divider)!!)
        recyclerview.addItemDecoration(div)
        recyclerview.itemAnimator = DefaultItemAnimator()
        doAsync {
            val result = readHttpText(GOODS_URL)
            if(result.isNotEmpty()){
                val array  = Gson().fromJson(result, JsonArray::class.java)
                uiThread {
                    for (v in array){
                        val obj = v as JsonObject
                        try {
                            val item =
                                GoodsItem(
                                    obj["good_name"].asString,
                                    obj["price"].asDouble.toString(),
                                    obj["discount"].asString,
                                    obj["star"].asBoolean
                                )
                            mDataSource.add(0,item)
                        }catch (exp:Exception){

                        }
                    }
                    mAdapter.notifyDataSetChanged()
                    if(mDataSource.isNotEmpty()){
                        recyclerview.smoothScrollToPosition(0)
                    }
                }
            }
        }
        logout.setOnClickListener {
            app().api.cleanLoginInfo()
            startActivity<LoginActivity>()
            finishAffinity()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
    }

}
