package de.schildbach.wallet.wallofcoins;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.widget.LinearLayoutManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import com.google.gson.Gson;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.uri.BitcoinURI;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.Nullable;

import de.schildbach.wallet.BuyDashPref;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.ExchangeRatesProvider;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.request.GetAuthTokenReq;
import de.schildbach.wallet.response.ConfirmDepositResp;
import de.schildbach.wallet.response.CountryData;
import de.schildbach.wallet.response.CreateHoldResp;
import de.schildbach.wallet.response.DiscoveryInputsResp;
import de.schildbach.wallet.response.GetAuthTokenResp;
import de.schildbach.wallet.response.GetOffersResp;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainStateLoader;
import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.ui.AddressAndLabel;
import de.schildbach.wallet.ui.CurrencyAmountView;
import de.schildbach.wallet.ui.CurrencyCalculatorLink;
import de.schildbach.wallet.ui.ExchangeRateLoader;
import de.schildbach.wallet.ui.WalletBalanceLoader;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.response.BuyDashErrorResp;
import de.schildbach.wallet.wallofcoins.response.CaptureHoldResp;
import de.schildbach.wallet.wallofcoins.response.CheckAuthResp;
import hashengineering.darkcoin.wallet.R;
import hashengineering.darkcoin.wallet.databinding.BuyDashFragmentBinding;
import okhttp3.Interceptor;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;


public final class BuyDashFragment extends Fragment implements OnSharedPreferenceChangeListener {
    private static final String TAG = BuyDashFragment.class.getSimpleName();
    private static final int ID_BALANCE_LOADER = 0;
    private static final int ID_RATE_LOADER = 1;
    private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;
    private static final int ID_ADDRESS_LOADER = 4;
    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;
    private Coin balance = null;
    private CurrencyCalculatorLink amountCalculatorLink;
    private BuyDashPref buyDashPref;
    private AddressAndLabel currentAddressQrAddress = null;

    private final LoaderManager.LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            return new ExchangeRateLoader(activity, config);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
            if (data != null && data.getCount() > 0) {
                data.moveToFirst();
                final ExchangeRatesProvider.ExchangeRate exchangeRate = ExchangeRatesProvider.getExchangeRate(data);

                amountCalculatorLink.setExchangeRate(exchangeRate.rate);
                updateView();
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> loader) {
        }
    };

    private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>() {
        @Override
        public Loader<Coin> onCreateLoader(final int id, final Bundle args) {
            return new WalletBalanceLoader(activity, wallet);
        }

        @Override
        public void onLoadFinished(final Loader<Coin> loader, final Coin balance) {
            BuyDashFragment.this.balance = balance;
            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<Coin> loader) {
        }
    };
    @Nullable
    private BlockchainState blockchainState = null;


    private final LoaderManager.LoaderCallbacks<BlockchainState> blockchainStateLoaderCallbacks = new LoaderManager.LoaderCallbacks<BlockchainState>() {
        @Override
        public Loader<BlockchainState> onCreateLoader(final int id, final Bundle args) {
            return new BlockchainStateLoader(activity);
        }

        @Override
        public void onLoadFinished(final Loader<BlockchainState> loader, final BlockchainState blockchainState) {
            BuyDashFragment.this.blockchainState = blockchainState;

            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<BlockchainState> loader) {
        }
    };

    private final LoaderManager.LoaderCallbacks<Address> addressLoaderCallbacks = new LoaderManager.LoaderCallbacks<Address>() {
        @Override
        public Loader<Address> onCreateLoader(final int id, final Bundle args) {
            return new CurrentAddressLoader(activity, application.getWallet(), config);
        }

        @Override
        public void onLoadFinished(Loader<Address> loader, Address currentAddress) {
            if (!currentAddress.equals(currentAddressQrAddress)) {

                currentAddressQrAddress = new AddressAndLabel(currentAddress, config.getOwnName());

                addressStr = BitcoinURI.convertToBitcoinURI(currentAddressQrAddress.address, null, currentAddressQrAddress.label, null);
            }
        }

        @Override
        public void onLoaderReset(Loader<Address> loader) {

        }
    };
    private CreateHoldResp createHoldResp;
    private String offerId;

    public static class CurrentAddressLoader extends AsyncTaskLoader<Address> {
        private LocalBroadcastManager broadcastManager;
        private final Wallet wallet;
        private Configuration config;

        private static final Logger log = LoggerFactory.getLogger(WalletBalanceLoader.class);

        public CurrentAddressLoader(final Context context, final Wallet wallet, final Configuration config) {
            super(context);

            this.broadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
            this.wallet = wallet;
            this.config = config;
        }

        @Override
        protected void onStartLoading() {
            super.onStartLoading();

            wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletChangeListener);
            wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletChangeListener);
            wallet.addChangeEventListener(Threading.SAME_THREAD, walletChangeListener);
            broadcastManager.registerReceiver(walletChangeReceiver, new IntentFilter(WalletApplication.ACTION_WALLET_REFERENCE_CHANGED));
            config.registerOnSharedPreferenceChangeListener(preferenceChangeListener);

            safeForceLoad();
        }

        @Override
        protected void onStopLoading() {
            config.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
            broadcastManager.unregisterReceiver(walletChangeReceiver);
            wallet.removeChangeEventListener(walletChangeListener);
            wallet.removeCoinsSentEventListener(walletChangeListener);
            wallet.removeCoinsReceivedEventListener(walletChangeListener);
            walletChangeListener.removeCallbacks();

            super.onStopLoading();
        }

        @Override
        protected void onReset() {
            config.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
            broadcastManager.unregisterReceiver(walletChangeReceiver);
            wallet.removeChangeEventListener(walletChangeListener);
            wallet.removeCoinsSentEventListener(walletChangeListener);
            wallet.removeCoinsReceivedEventListener(walletChangeListener);
            walletChangeListener.removeCallbacks();

            super.onReset();
        }

        @Override
        public Address loadInBackground() {
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

            return wallet.currentReceiveAddress();
        }

        private final ThrottlingWalletChangeListener walletChangeListener = new ThrottlingWalletChangeListener() {
            @Override
            public void onThrottledWalletChanged() {
                safeForceLoad();
            }
        };

        private final BroadcastReceiver walletChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                safeForceLoad();
            }
        };

        private final OnSharedPreferenceChangeListener preferenceChangeListener = new OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
                if (Configuration.PREFS_KEY_OWN_NAME.equals(key))
                    safeForceLoad();
            }
        };

        private void safeForceLoad() {
            try {
                forceLoad();
            } catch (final RejectedExecutionException x) {
                log.info("rejected execution: " + BuyDashFragment.CurrentAddressLoader.this.toString());
            }
        }
    }

    @Nullable
    private String defaultCurrency = null;
    private BuyDashFragmentBinding binding;

    private String token;
    private Interceptor interceptor = new Interceptor() {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request original = chain.request();

            // Request customization: add request headers
            Request.Builder requestBuilder = original.newBuilder()
                    .addHeader("X-Coins-Api-Token", buyDashPref.getAuthToken());

            Request request = requestBuilder.build();
            return chain.proceed(request);
        }
    };

    private CountryData countryData;
    private String addressStr = "";

    @Override
    public void onAttach(final Context context) {
        super.onAttach(context);

        this.activity = (AbstractWalletActivity) context;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();

        this.wallet = application.getWallet();
        this.loaderManager = getLoaderManager();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.buyDashPref = new BuyDashPref(PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext()));

        setRetainInstance(true);
        setHasOptionsMenu(true);


        defaultCurrency = config.getExchangeCurrencyCode();
        config.registerOnSharedPreferenceChangeListener(this);
        buyDashPref.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {

        binding = DataBindingUtil.inflate(inflater, R.layout.buy_dash_fragment, container, false);

        String json = null;
        try {
            InputStream is = activity.getAssets().open("countries.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            json = new String(buffer, "UTF-8");
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }

        countryData = new Gson().fromJson(json, CountryData.class);

        List<String> stringList = new ArrayList<>();

        for (CountryData.CountriesBean bean : countryData.countries) {
            stringList.add(bean.name + " (" + bean.code + ")");
        }

        ArrayAdapter<String> countryAdapter = new ArrayAdapter<String>(activity,
                android.R.layout.simple_spinner_dropdown_item, stringList);
        countryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spCountry.setAdapter(countryAdapter);

        binding.requestCoinsAmountBtc.setCurrencySymbol(config.getFormat().code());
        binding.requestCoinsAmountBtc.setInputFormat(config.getMaxPrecisionFormat());
        binding.requestCoinsAmountBtc.setHintFormat(config.getFormat());

        binding.requestCoinsAmountLocal.setInputFormat(Constants.LOCAL_FORMAT);
        binding.requestCoinsAmountLocal.setHintFormat(Constants.LOCAL_FORMAT);
        amountCalculatorLink = new CurrencyCalculatorLink(binding.requestCoinsAmountBtc, binding.requestCoinsAmountLocal);

        binding.rvOffers.setLayoutManager(new LinearLayoutManager(activity));

        binding.buttonBuyDashGetOffers.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (TextUtils.isEmpty(binding.buyDashZip.getText().toString().trim())) {
                    Toast.makeText(activity, "Please Enter Zip Code!", Toast.LENGTH_LONG).show();
                    return;
                }
                callDiscoveryInputs();
            }
        });

        binding.buttonVerifyOtp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HashMap<String, String> captureHoldReq = new HashMap<String, String>();
                String otp = binding.etOtp.getText().toString().trim();
                if (TextUtils.isEmpty(otp)) {
                    Toast.makeText(activity, "Please Enter The OTP!", Toast.LENGTH_LONG).show();
                    return;
                }
                captureHoldReq.put("publisherId", addressStr);
                captureHoldReq.put("verificationCode", otp);

                binding.buyDashProgress.setVisibility(View.VISIBLE);
                WallofCoins.createService(interceptor, getActivity()).captureHold(createHoldResp.id, captureHoldReq)
                        .enqueue(new Callback<List<CaptureHoldResp>>() {
                            @Override
                            public void onResponse(Call<List<CaptureHoldResp>> call, Response<List<CaptureHoldResp>> response) {
                                if (null != response && null != response.body() && !response.body().isEmpty()) {

                                    confirmDeposit(response);

                                } else if (null != response && null != response.errorBody()) {
                                    binding.buyDashProgress.setVisibility(View.GONE);
                                    try {
                                        BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                                        Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                    }
                                } else {
                                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                    binding.buyDashProgress.setVisibility(View.GONE);
                                }
                            }

                            @Override
                            public void onFailure(Call<List<CaptureHoldResp>> call, Throwable t) {
                                binding.buyDashProgress.setVisibility(View.GONE);
                                Log.e(TAG, "onFailure: ", t);
                            }
                        });
            }
        });


        return binding.getRoot();
    }

    private void confirmDeposit(Response<List<CaptureHoldResp>> response) {
        WallofCoins.createService(interceptor, getActivity()).confirmDeposit("" + response.body().get(0).id, "").enqueue(new Callback<ConfirmDepositResp>() {
            @Override
            public void onResponse(Call<ConfirmDepositResp> call, Response<ConfirmDepositResp> response) {

                binding.buyDashProgress.setVisibility(View.GONE);

                if (null != response && null != response.body()) {

                    binding.setConfiremedData(response.body());
                    binding.layoutVerifyOtp.setVisibility(View.GONE);
                    binding.layoutCompletionDetail.setVisibility(View.VISIBLE);

                } else if (null != response && null != response.errorBody()) {

                    try {
                        BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                        Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }

                } else {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<ConfirmDepositResp> call, Throwable t) {
                binding.buyDashProgress.setVisibility(View.GONE);
                Log.e(TAG, "onFailure: ", t);
            }
        });
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        amountCalculatorLink.setExchangeDirection(config.getLastExchangeDirection());
        amountCalculatorLink.requestFocus();
    }

    @Override
    public void onResume() {
        super.onResume();

        amountCalculatorLink.setListener(new CurrencyAmountView.Listener() {
            @Override
            public void changed() {
                updateView();
            }

            @Override
            public void focusChanged(final boolean hasFocus) {
            }
        });
        loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);

        loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);
        loaderManager.initLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);
        loaderManager.initLoader(ID_ADDRESS_LOADER, null, addressLoaderCallbacks);

        updateView();
    }

    @Override
    public void onPause() {

        loaderManager.destroyLoader(ID_BALANCE_LOADER);
        loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);
        loaderManager.destroyLoader(ID_ADDRESS_LOADER);

        amountCalculatorLink.setListener(null);

        super.onPause();
    }

    @Override
    public void onDestroy() {
        config.unregisterOnSharedPreferenceChangeListener(this);

        config.setLastExchangeDirection(amountCalculatorLink.getExchangeDirection());
        loaderManager.destroyLoader(ID_RATE_LOADER);

        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_EXCHANGE_CURRENCY.equals(key) || Configuration.PREFS_KEY_BTC_PRECISION.equals(key)) {
            defaultCurrency = config.getExchangeCurrencyCode();

            updateView();
        }
    }

    private void updateView() {
        balance = application.getWallet().getBalance(BalanceType.ESTIMATED);
    }

    private void callDiscoveryInputs() {

        HashMap<String, String> discoveryInputsReq = new HashMap<String, String>();

        discoveryInputsReq.put("publisherId", addressStr);
        try {
            discoveryInputsReq.put("usdAmount", "" + binding.requestCoinsAmountLocal.getTextView().getHint());
        } catch (Exception e) {
            discoveryInputsReq.put("usdAmount", "0");
            e.printStackTrace();
        }
        discoveryInputsReq.put("crypto", "DASH");
        discoveryInputsReq.put("bank", "");
        discoveryInputsReq.put("zipCode", binding.buyDashZip.getText().toString());

        binding.buyDashProgress.setVisibility(View.VISIBLE);

        WallofCoins.createService(getActivity()).discoveryInputs(discoveryInputsReq).enqueue(new Callback<DiscoveryInputsResp>() {
            @Override
            public void onResponse(Call<DiscoveryInputsResp> call, Response<DiscoveryInputsResp> response) {

                if (null != response && null != response.body()) {

                    if (null != response.body().id) {
                        WallofCoins.createService(getActivity()).getOffers(response.body().id).enqueue(new Callback<GetOffersResp>() {
                            @Override
                            public void onResponse(Call<GetOffersResp> call, final Response<GetOffersResp> response) {

                                if (null != response && null != response.body()) {

                                    binding.buyDashProgress.setVisibility(View.GONE);

                                    if (null != response.body().singleDeposit && !response.body().singleDeposit.isEmpty()) {
                                        binding.buttonBuyDashGetOffers.setVisibility(View.GONE);
                                        binding.buyDashZip.setVisibility(View.GONE);
                                        binding.linearCurrency.setVisibility(View.GONE);
                                        BuyDashOffersAdapter buyDashOffersAdapter = new BuyDashOffersAdapter(activity, response.body().singleDeposit, new AdapterView.OnItemSelectedListener() {
                                            @Override
                                            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                                offerId = response.body().singleDeposit.get(position).id;
                                                createHold();
                                            }

                                            @Override
                                            public void onNothingSelected(AdapterView<?> parent) {
                                                binding.buyDashProgress.setVisibility(View.GONE);
                                            }
                                        });
                                        binding.rvOffers.setAdapter(buyDashOffersAdapter);
                                    } else {
                                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                    }
                                } else if (null != response && null != response.errorBody()) {
                                    binding.buyDashProgress.setVisibility(View.GONE);
                                    try {
                                        BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                                        Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                    }

                                } else {
                                    binding.buyDashProgress.setVisibility(View.GONE);
                                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                }
                            }

                            @Override
                            public void onFailure(Call<GetOffersResp> call, Throwable t) {
                                binding.buyDashProgress.setVisibility(View.GONE);
                                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                            }
                        });
                    } else {
                        binding.buyDashProgress.setVisibility(View.GONE);
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }
                } else if (null != response && null != response.errorBody()) {

                    binding.buyDashProgress.setVisibility(View.GONE);

                    try {
                        BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                        Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }

                } else {
                    binding.buyDashProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<DiscoveryInputsResp> call, Throwable t) {
                binding.buyDashProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });

    }

    private void getAuthTokenCall() {
        String countryCode = countryData.countries.get(binding.spCountry.getSelectedItemPosition()).code;
        String phone = countryCode + binding.editBuyDashPhone.getText().toString().trim();
        String password = binding.etPassword.getText().toString().trim();

        if (!TextUtils.isEmpty(phone) && !TextUtils.isEmpty(password)) {
            final GetAuthTokenReq getAuthTokenReq = new GetAuthTokenReq();
            getAuthTokenReq.password = password;
            binding.buyDashProgress.setVisibility(View.VISIBLE);
            WallofCoins.createService(getActivity()).getAuthToken(phone, getAuthTokenReq).enqueue(new Callback<GetAuthTokenResp>() {
                @Override
                public void onResponse(Call<GetAuthTokenResp> call, Response<GetAuthTokenResp> response) {

                    int code = response.code();

                    if (code >= 400 && response.body() == null) {
                        try {
                            BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                            Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            e.printStackTrace();
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        }
                        binding.buyDashProgress.setVisibility(View.GONE);
                        return;
                    }

                    buyDashPref.setAuthToken(response.body().token);
                    // call create hold
                    createHold();
                }

                @Override
                public void onFailure(Call<GetAuthTokenResp> call, Throwable t) {
                    binding.buyDashProgress.setVisibility(View.GONE);
                }
            });

        } else {
            Toast.makeText(activity, "Phone and Password is required", Toast.LENGTH_SHORT).show();
        }

    }

    private void createHold() {
        if (buyDashPref.getAuthToken() != null && !TextUtils.isEmpty(buyDashPref.getAuthToken())) {
            final HashMap<String, String> createHoldReq = new HashMap<String, String>();
            createHoldReq.put("offer", offerId);
            createHoldReq.put("X-Coins-Api-Token", buyDashPref.getAuthToken());
            binding.buyDashProgress.setVisibility(View.VISIBLE);
            WallofCoins.createService(interceptor, getActivity()).createHold(createHoldReq).enqueue(new Callback<CreateHoldResp>() {
                @Override
                public void onResponse(Call<CreateHoldResp> call, Response<CreateHoldResp> response) {
                    binding.buyDashProgress.setVisibility(View.GONE);

                    if (response.code() == 403) {
                        binding.rvOffers.setVisibility(View.GONE);
                        binding.linearPhone.setVisibility(View.VISIBLE);
                        binding.btnNextPhone.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                checkAuth();
                            }
                        });
                        return;
                    }
                    if (null != response.body()) {
                        createHoldResp = response.body();
                        buyDashPref.setCreateHoldResp(createHoldResp);
                        binding.layoutCreateHold.setVisibility(View.GONE);
                        binding.layoutVerifyOtp.setVisibility(View.VISIBLE);
//                        binding.etOtp.setText(createHoldResp.__PURCHASE_CODE);
//                        Log.d(TAG, "onResponse: purchase code==>>" + createHoldResp.__PURCHASE_CODE);
                    } else if (null != response.errorBody()) {
                        try {
                            BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                            Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            e.printStackTrace();
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onFailure(Call<CreateHoldResp> call, Throwable t) {
                    binding.buyDashProgress.setVisibility(View.GONE);
                    Toast.makeText(activity, R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            binding.rvOffers.setVisibility(View.GONE);
            binding.linearPhone.setVisibility(View.VISIBLE);
            binding.btnNextPhone.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    checkAuth();
                }
            });
        }
    }

    public void createHoldWithPassword() {
        String countryCode = countryData.countries.get(binding.spCountry.getSelectedItemPosition()).code;
        String phone = countryCode + binding.editBuyDashPhone.getText().toString().trim();
        String password = binding.etPassword.getText().toString().trim();

        if (!TextUtils.isEmpty(phone) && !TextUtils.isEmpty(password)) {
            final HashMap<String, String> createHoldPassReq = new HashMap<String, String>();
            createHoldPassReq.put("offer", offerId);
            createHoldPassReq.put("phone", phone);
            createHoldPassReq.put("password", password);
            binding.buyDashProgress.setVisibility(View.VISIBLE);

            WallofCoins.createService(interceptor, getActivity()).createHold(createHoldPassReq).enqueue(new Callback<CreateHoldResp>() {
                @Override
                public void onResponse(Call<CreateHoldResp> call, Response<CreateHoldResp> response) {
                    binding.buyDashProgress.setVisibility(View.GONE);

                    if (response.code() == 403) {
                        binding.rvOffers.setVisibility(View.GONE);
                        binding.linearPhone.setVisibility(View.VISIBLE);
                        binding.btnNextPhone.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                checkAuth();
                            }
                        });
                        return;
                    }
                    if (null != response.body()) {
                        createHoldResp = response.body();
                        buyDashPref.setCreateHoldResp(createHoldResp);
                        binding.layoutCreateHold.setVisibility(View.GONE);
                        binding.layoutVerifyOtp.setVisibility(View.VISIBLE);
//                        Log.d(TAG, "onResponse: purchase code==>>" + createHoldResp.__PURCHASE_CODE);
//                        binding.etOtp.setText(createHoldResp.__PURCHASE_CODE);
                    } else if (null != response.errorBody()) {
                        try {
                            BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                            Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            e.printStackTrace();
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onFailure(Call<CreateHoldResp> call, Throwable t) {
                    binding.buyDashProgress.setVisibility(View.GONE);
                    Toast.makeText(activity, R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Toast.makeText(activity, "Create Password", Toast.LENGTH_SHORT).show();
        }

    }

    public void checkAuth() {
        String countryCode = countryData.countries.get(binding.spCountry.getSelectedItemPosition()).code;
        String phone = countryCode + binding.editBuyDashPhone.getText().toString().trim();
        if (!TextUtils.isEmpty(phone)) {
            binding.buyDashProgress.setVisibility(View.VISIBLE);

            WallofCoins.createService(activity).checkAuth(phone).enqueue(new Callback<CheckAuthResp>() {
                @Override
                public void onResponse(Call<CheckAuthResp> call, Response<CheckAuthResp> response) {
                    Log.d(TAG, "onResponse: response code==>>" + response.code());
                    binding.buyDashProgress.setVisibility(View.GONE);
                    if (response.code() == 200) {
                        if (response.body() != null && response.body().getAvailableAuthSources() != null && response.body().getAvailableAuthSources().size() > 0) {
                            if (response.body().getAvailableAuthSources().get(0).equals("password")) {
                                binding.linearPhone.setVisibility(View.GONE);
                                binding.etPassword.setHint("Enter Password");
                                binding.linearPassword.setVisibility(View.VISIBLE);

                                binding.btnNextPassword.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        getAuthTokenCall();
                                    }
                                });
                            }
                        }
                    } else if (response.code() == 404) {
                        binding.linearPhone.setVisibility(View.GONE);
                        binding.etPassword.setHint("Create Password");
                        binding.linearPassword.setVisibility(View.VISIBLE);
                        binding.btnNextPassword.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                createHoldWithPassword();
                            }
                        });
                    }
                }

                @Override
                public void onFailure(Call<CheckAuthResp> call, Throwable t) {
                    binding.buyDashProgress.setVisibility(View.GONE);
                    Toast.makeText(activity, R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Toast.makeText(activity, "Enter phone number", Toast.LENGTH_SHORT).show();
        }
    }
}
