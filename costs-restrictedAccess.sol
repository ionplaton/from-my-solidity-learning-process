//SPDX-License-Identifier: MIT
pragma solidity ^0.8.18;

contract RestrictedAccess {

// There will be a variable called owner. I will set the owner to be the owner of the contract (msg.sender)
    address public owner = msg.sender;
    
/*
I will create a modifier called costs which takes an _amount parameter and require that msg.value is greater 
than or equal to the amount. If the msg.value is not greater than or equal to the amount, then return a 
string that says "Not enough Ether provided!".
*/
    modifier costs(uint _amount) {
        require(msg.value >= _amount,
        'Not enough Ether provided!'
        );
        _;
    }

/*
I will write a payable function called forceOwnerChange which takes an address called _newOwner. 
The function needs 200 Ether to set the owner to the contract to the new owner of the address.
*/
    function forceOwnerChange (address _newOwner) payable public costs(200 ether) {
        owner = _newOwner;
    }
}
